package com.example.myapplication

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.SettingsStore
import com.example.myapplication.databinding.ActivityLoginBinding

/**
 * 登录页：在 WebView 内打开抖音，用户用已登录的抖音 App 扫二维码
 * （或验证码登录）后，自动检测 sessionid 并提取全部登录态 Cookie 保存。
 *
 * 为什么不能直接"拉起抖音 App 并读取它的登录态"？
 * - Android 沙箱隔离：抖音 App 的登录 Cookie 存于其私有目录，外部应用无法读取。
 * - 抖音未向个人开发者开放授权接口。
 * 因此最稳的方案是扫码登录：网页二维码 + 已登录抖音 App 确认，几秒完成。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var store: SettingsStore
    private val handler = Handler(Looper.getMainLooper())

    /** 是否已成功提取并保存 Cookie（避免重复保存）。 */
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener {
            binding.tvStatus.text = "正在加载…"
            binding.tvStatus.setTextColor(getColor(R.color.status_pending))
            binding.webView.reload()
        }

        setupWebView()
        binding.webView.loadUrl(LOGIN_URL)
        handler.post(checkLogin)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.setSupportMultipleWindows(false)
        // 使用桌面 UA，保证打开的是桌面版页面（收藏 tab / listcollection 接口都在桌面版）
        ws.userAgentString = DESKTOP_UA

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.tvStatus.text = "正在加载…"
                binding.tvStatus.setTextColor(getColor(R.color.status_pending))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.tvStatus.text = "等待登录…"
                binding.tvStatus.setTextColor(getColor(R.color.status_pending))
                maybeSave()
            }

            @SuppressLint("WebViewClientOnReceivedError")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                // 只在主页面加载失败时提示（子资源失败会频繁触发，忽略）
                if (failingUrl?.startsWith("https://www.douyin.com") == true) {
                    binding.tvStatus.text = "加载失败：$description"
                    binding.tvStatus.setTextColor(getColor(R.color.status_error))
                }
            }
        }
    }

    private val checkLogin = object : Runnable {
        override fun run() {
            if (isFinishing || saved) return
            maybeSave()
            if (!saved) handler.postDelayed(this, 2000)
        }
    }

    /** 从 CookieManager 读取 Cookie，检测到 sessionid 即视为登录成功。 */
    private fun maybeSave() {
        if (saved) return
        val cookieStr = CookieManager.getInstance().getCookie("https://www.douyin.com")
        if (cookieStr.isNullOrEmpty()) return

        val map = parseCookies(cookieStr)
        val sid = map["sessionid"]
        if (sid.isNullOrBlank()) {
            binding.tvStatus.text = "等待登录…"
            return
        }

        saved = true
        handler.removeCallbacks(checkLogin)
        store.saveCookies(map)
        binding.tvStatus.text = "✓ 已保存 ${map.size} 个 Cookie"
        binding.tvStatus.setTextColor(getColor(R.color.status_ok))
        Toast.makeText(this, "已提取登录 Cookie，即将返回", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ finish() }, 1200)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://www.douyin.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 解析 "a=1; b=2" 形式的 Cookie 字符串。 */
        fun parseCookies(s: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            s.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) map[kv[0]] = kv[1]
            }
            return map
        }
    }
}
