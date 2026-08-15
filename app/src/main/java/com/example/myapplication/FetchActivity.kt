package com.example.myapplication

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.FavoriteItem
import com.example.myapplication.data.SettingsStore
import com.example.myapplication.databinding.ActivityFetchBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏抓取页：在 WebView 内打开抖音「收藏」页，自动滚动触发翻页，
 * 通过 JS hook 捕获 listcollection 接口的响应体，直到 has_more=0 或超时。
 *
 * 为什么不能直接发 HTTP 请求？
 * 抖音收藏接口 /aweme/v1/web/aweme/listcollection/ 带 a_bogus 签名，
 * 该签名由页面内打包的 JS 生成，纯 HTTP 请求会被拒绝。
 * 因此采用与 PC 端 douyin-tools（Playwright 方案）一致的思路：
 * 让页面自己发请求，我们监听响应 + 滚动翻页。
 */
class FetchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFetchBinding
    private lateinit var store: SettingsStore
    private val handler = Handler(Looper.getMainLooper())

    /** aweme_id -> 收藏项（天然去重）。 */
    private val allItems = LinkedHashMap<String, FavoriteItem>()
    private var finished = false
    private var emptyRounds = 0
    private var lastCount = 0
    private var domSeeded = false

    /** 访客模式：免登录抓取他人公开收藏。 */
    private var guestMode = false

    /** 访客模式下是否仍在等待从页面解析 sec_uid。 */
    private var guestResolving = false

    /** 是否已加载到收藏页（自我收藏页或他人收藏页），可开始抓取。 */
    private var profileLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFetchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnCancel.setOnClickListener { finishFetch(canceled = true) }
        setupWebView()

        // 接口对象必须在 loadUrl 之前注入，对所有页面生效
        binding.webView.addJavascriptInterface(Bridge(), "DyBridge")

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SELF
        if (mode == MODE_GUEST) {
            guestMode = true
            binding.tvTitle.text = "抓取公开收藏"
            binding.tvHint.text =
                "正在打开对方主页的「收藏」Tab 并自动捕获数据…仅限对方开启「公开收藏」，请保持页面可见。"
            startGuest(intent.getStringExtra(EXTRA_GUEST_INPUT)?.trim().orEmpty())
        } else {
            injectCookies()
            binding.webView.loadUrl(FAVORITES_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.setSupportMultipleWindows(false)
        ws.userAgentString = DESKTOP_UA

        // 页面加载完成后：注入 hook -> 提取首屏 DOM 兜底 -> 开始滚动翻页
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 访客模式待解析：先尝试从当前页解析 sec_uid，再进入收藏页
                if (guestMode && guestResolving) {
                    handler.postDelayed({ resolveGuestFromPage(url) }, 2500)
                    return
                }
                // 收藏页加载完成：注入 hook -> 提取首屏 DOM 兜底 -> 开始滚动翻页
                if (!profileLoaded) return
                handler.postDelayed({
                    if (!finished) {
                        injectHook()
                        seedFromDom()
                        startScrolling()
                    }
                }, 3000)
            }
        }
    }

    /** 把本地保存的 Cookie 注入 CookieManager，抓取时保持登录态。 */
    private fun injectCookies() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookies(null)
        store.loadCookies().forEach { (k, v) ->
            cm.setCookie("https://www.douyin.com", "$k=$v; Domain=.douyin.com; Path=/")
        }
        cm.flush()
    }

    // ------------------------------------------------------------------
    // 访客模式：免登录抓取他人公开收藏
    // ------------------------------------------------------------------

    /** 解析输入并打开目标用户主页收藏页。 */
    private fun startGuest(input: String) {
        // 不注入任何登录 Cookie，保持「免登录」语义
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookies(null)
        cm.flush()

        val secUid = extractSecUid(input)
        if (secUid != null) {
            loadGuestProfile(secUid)
            return
        }
        // 短链或抖音号：先打开页面，稍后从 URL / DOM 解析 sec_uid
        guestResolving = true
        val url = if (input.contains("douyin.com")) {
            input
        } else {
            "https://www.douyin.com/search/" + Uri.encode(input)
        }
        binding.webView.loadUrl(url)
    }

    /** 从输入中提取 sec_uid；无法确定时返回 null。 */
    private fun extractSecUid(input: String): String? {
        // 主页链接：douyin.com/user/{secUid}
        Regex("douyin\\.com/user/([A-Za-z0-9_\\-]+)").find(input)?.let {
            val id = it.groupValues[1]
            if (id != "self") return id
        }
        // 特征明显的 sec_uid（通常含 MS4wLjAB 或较长）
        if (input.contains("MS4wLjAB") ||
            (input.length >= 24 && Regex("^[A-Za-z0-9_\\-]+$").matches(input))
        ) {
            return input
        }
        return null
    }

    /** 打开用户主页的「收藏」Tab。 */
    private fun loadGuestProfile(secUid: String) {
        profileLoaded = true
        binding.webView.loadUrl(
            "https://www.douyin.com/user/${Uri.encode(secUid)}?showTab=favorite_collection"
        )
    }

    /** 从已打开页面（短链重定向页 / 搜索页）解析出 sec_uid，再进入收藏页。 */
    private fun resolveGuestFromPage(currentUrl: String?) {
        if (finished) return
        // 1) 当前 URL 已是用户主页
        Regex("douyin\\.com/user/([A-Za-z0-9_\\-]+)").find(currentUrl ?: "")?.let {
            val id = it.groupValues[1]
            if (id != "self") {
                guestResolving = false
                loadGuestProfile(id)
                return
            }
        }
        // 2) 从页面 DOM 提取用户卡片链接（搜索页场景）
        binding.webView.evaluateJavascript(RESOLVE_JS) { value ->
            val secUid = Regex("\"secUid\"\\s*:\\s*\"([^\"]+)\"").find(value ?: "")
                ?.groupValues?.get(1)
            if (!secUid.isNullOrEmpty()) {
                guestResolving = false
                loadGuestProfile(secUid)
            } else {
                guestResolving = false
                Toast.makeText(
                    this,
                    "无法解析该用户主页，请直接粘贴完整主页链接",
                    Toast.LENGTH_LONG
                ).show()
                finishFetch(canceled = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // JS 桥
    // ------------------------------------------------------------------
    private inner class Bridge {
        @JavascriptInterface
        fun onCollection(json: String) {
            runOnUiThread { handleCollection(json) }
        }
    }

    private fun handleCollection(json: String) {
        if (finished) return
        try {
            val o = JSONObject(json)
            val items = o.optJSONArray("items") ?: JSONArray()
            val hasMore = o.optBoolean("has_more", true)
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val id = obj.optString("aweme_id")
                if (id.isNotEmpty()) {
                    allItems[id] = FavoriteItem(
                        awemeId = id,
                        desc = obj.optString("desc", ""),
                        url = "https://www.douyin.com/video/$id",
                        author = obj.optString("author", ""),
                        awemeType = obj.optInt("aweme_type", 0),
                    )
                }
            }
            updateProgress()
            if (!hasMore) finishFetch(canceled = false)
        } catch (e: Exception) {
            // 忽略解析错误，等待下一轮
        }
    }

    // ------------------------------------------------------------------
    // 抓取流程
    // ------------------------------------------------------------------

    /** 注入 fetch/XHR hook，捕获 listcollection 响应并回传原生层。 */
    private fun injectHook() {
        binding.webView.evaluateJavascript(HOOK_JS, null)
    }

    /** 从已渲染 DOM 提取首屏 video id（兜底：hook 可能漏掉第一页请求）。 */
    private fun seedFromDom() {
        if (domSeeded) return
        domSeeded = true
        binding.webView.evaluateJavascript(DOM_SEED_JS) { value ->
            runOnUiThread {
                try {
                    val ids = JSONObject(value).getJSONArray("ids")
                    var added = 0
                    for (i in 0 until ids.length()) {
                        val id = ids.optString(i)
                        if (id.isNotEmpty() && !allItems.containsKey(id)) {
                            allItems[id] = FavoriteItem(
                                awemeId = id,
                                desc = "（来自页面）",
                                url = "https://www.douyin.com/video/$id",
                            )
                            added++
                        }
                    }
                    if (added > 0) {
                        emptyRounds = 0
                        updateProgress()
                    }
                } catch (e: Exception) {
                    // DOM 结构变化时兜底失败，可接受
                }
            }
        }
    }

    private fun startScrolling() {
        handler.post(scrollTask)
    }

    private val scrollTask = object : Runnable {
        override fun run() {
            if (finished) return

            binding.webView.evaluateJavascript(SCROLL_JS, null)

            if (allItems.size == lastCount) {
                emptyRounds++
            } else {
                emptyRounds = 0
                lastCount = allItems.size
            }
            updateProgress()

            val noDataAtAll = allItems.isEmpty() && emptyRounds >= 3
            val stuck = allItems.isNotEmpty() && emptyRounds >= MAX_EMPTY_ROUNDS
            if (noDataAtAll || stuck) {
                finishFetch(canceled = false)
            } else {
                handler.postDelayed(this, SCROLL_INTERVAL_MS)
            }
        }
    }

    private fun updateProgress() {
        val n = allItems.size
        binding.tvProgress.text =
            if (n == 0) "等待数据…" else "已获取 $n 条"
    }

    private fun finishFetch(canceled: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacks(scrollTask)
        handler.removeCallbacksAndMessages(null)

        if (allItems.isNotEmpty()) {
            store.saveFavorites(allItems.values.toList())
            store.lastUpdatedAt = System.currentTimeMillis()
            Toast.makeText(this, "已保存 ${allItems.size} 条收藏", Toast.LENGTH_SHORT).show()
        } else {
            val msg = when {
                canceled -> "已取消"
                guestMode -> "未获取到数据（对方收藏可能未公开，或未开启「公开收藏」）"
                else -> "未获取到数据（可能 Cookie 已过期，请重新登录）"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_GUEST_INPUT = "extra_guest_input"
        const val MODE_SELF = "self"
        const val MODE_GUEST = "guest"

        private const val FAVORITES_URL =
            "https://www.douyin.com/user/self?showTab=favorite_collection"
        private const val SCROLL_INTERVAL_MS = 2500L
        private const val MAX_EMPTY_ROUNDS = 8
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 捕获 fetch / XHR 中的 listcollection 响应。 */
        private const val HOOK_JS = """
            (function() {
                if (window.__dyHooked) return;
                window.__dyHooked = true;

                function handle(url, body) {
                    if (!url || !body || !body.aweme_list) return;
                    if (String(url).indexOf('listcollection') === -1) return;
                    var list = body.aweme_list;
                    var hasMore = body.has_more;
                    var items = [];
                    for (var i = 0; i < list.length; i++) {
                        var a = list[i];
                        items.push({
                            aweme_id: a.aweme_id,
                            desc: (a.desc || '').trim(),
                            author: (a.author && a.author.nickname) || '',
                            aweme_type: a.aweme_type
                        });
                    }
                    if (window.DyBridge && window.DyBridge.onCollection) {
                        window.DyBridge.onCollection(JSON.stringify({items: items, has_more: hasMore}));
                    }
                }

                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        var url = arguments[0];
                        var p = origFetch.apply(this, arguments);
                        if (typeof url === 'string' && url.indexOf('listcollection') !== -1) {
                            p.then(function(resp) {
                                try {
                                    resp.clone().text().then(function(t) { handle(url, JSON.parse(t)); });
                                } catch (e) {}
                            });
                        }
                        return p;
                    };
                }

                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__dyUrl = url;
                    return origOpen.apply(this, arguments);
                };
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    var self = this;
                    this.addEventListener('load', function() {
                        try {
                            var u = self.__dyUrl || '';
                            if (u.indexOf('listcollection') !== -1) {
                                handle(u, JSON.parse(self.responseText));
                            }
                        } catch (e) {}
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """

        /** 提取首屏已渲染卡片的 video id。 */
        private const val DOM_SEED_JS = """
            (function() {
                var ids = [];
                var links = document.querySelectorAll('a[href*="/video/"]');
                for (var i = 0; i < links.length; i++) {
                    var h = links[i].getAttribute('href');
                    var m = h && h.match(/\/video\/(\d+)/);
                    if (m && ids.indexOf(m[1]) === -1) ids.push(m[1]);
                }
                return {ids: ids};
            })();
        """

        /** 从搜索页/主页 DOM 中提取第一个用户主页链接的 sec_uid。 */
        private const val RESOLVE_JS = """
            (function() {
                var links = document.querySelectorAll('a[href*="/user/"]');
                for (var i = 0; i < links.length; i++) {
                    var h = links[i].getAttribute('href') || '';
                    var m = h.match(/\/user\/([A-Za-z0-9_\-]+)/);
                    if (m && m[1] && m[1] !== 'self') {
                        return {secUid: m[1]};
                    }
                }
                return {secUid: ''};
            })();
        """

        /** 滚动所有可滚动容器到底部，触发翻页。 */
        private const val SCROLL_JS = """
            (function() {
                var all = document.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (el.scrollHeight > el.clientHeight + 100) el.scrollTop = el.scrollHeight;
                }
                window.scrollTo(0, document.body.scrollHeight);
            })();
        """
    }
}
