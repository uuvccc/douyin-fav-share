package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.SettingsStore
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.update.UpdateManager
import org.json.JSONObject

/**
 * 主界面：三步完成「自动获取 Cookie -> 抓取收藏 -> 随机分享」。
 *
 * 1. 登录：内嵌 WebView 登录抖音，自动提取 Cookie（也可从 PC 端 douyin-tools
 *    生成的 douyin_cookies.txt 内容粘贴导入）。
 * 2. 抓取：WebView 打开收藏页，自动滚动翻页并捕获收藏列表。
 * 3. 分享：从收藏中随机选一条，复制链接到剪贴板。
 *
 * 自动更新：启动时（仅 CI 构建）与菜单手动触发都会检查 GitHub Release，
 * 有新版本则提示下载（走公开镜像加速）并拉起系统安装器。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore
    private lateinit var updateManager: UpdateManager

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    private val fetchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    /** 扫码获取 Cookie：扫 PC 端 douyin_cookie_qr.py 生成的二维码。 */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "扫码已取消", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        importCookieText(text.trim())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        updateManager = UpdateManager(applicationContext)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnLogin.setOnClickListener {
            try {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开登录页：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnImportCookie.setOnClickListener { showImportCookieDialog() }
        binding.btnScanCookie.setOnClickListener {
            try {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("扫描 PC 屏幕上的二维码获取 Cookie")
                    setBeepEnabled(true)
                    setOrientationLocked(false)
                }
                scanLauncher.launch(options)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开相机：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnFetch.setOnClickListener { showFetchModeDialog() }
        binding.btnShare.setOnClickListener { shareRandom() }

        // 自动检查更新：仅对 CI 构建生效（本地开发版跳过，避免打扰）
        if (BuildConfig.CI_BUILD_ID > 0L) {
            binding.toolbar.postDelayed({ checkForUpdates(manual = false) }, 3000)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_check_update -> {
                checkForUpdates(manual = true)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        // 登录状态
        binding.tvLoginStatus.text = if (store.hasSession()) {
            "已登录（sessionid ${store.loadCookies()["sessionid"]?.take(8)}…）"
        } else {
            "未登录"
        }
        binding.tvLoginStatus.setTextColor(
            if (store.hasSession()) getColor(R.color.status_ok)
            else getColor(R.color.status_pending)
        )

        // 收藏状态
        val favs = store.loadFavorites()
        binding.tvFetchStatus.text = if (favs.isEmpty()) {
            "暂无收藏数据"
        } else {
            val t = store.lastUpdatedAt
            val time = if (t > 0) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(t))
            } else "未知"
            "已保存 ${favs.size} 条收藏（$time 更新）"
        }
        binding.tvFetchStatus.setTextColor(
            if (favs.isEmpty()) getColor(R.color.status_pending)
            else getColor(R.color.status_ok)
        )

        // 随机分享按钮可用性
        binding.btnShare.isEnabled = favs.isNotEmpty()
    }

    /** 从收藏中随机选一条，复制链接到剪贴板。 */
    private fun shareRandom() {
        val favs = store.loadFavorites()
        if (favs.isEmpty()) {
            Toast.makeText(this, "还没有收藏数据，请先抓取收藏", Toast.LENGTH_SHORT).show()
            return
        }
        val item = favs.random()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // 抖音系 App（含抖音精选）通过剪贴板监听识别分享口令：文本内包含
        // douyin.com 链接即可触发弹窗。采用官方口令的文本结构，识别率最高。
        val shareText = buildString {
            append("复制打开抖音精选，看看TA的作品 ")
            append(item.url)
            append(" 复制此链接，打开抖音精选，直接观看视频！")
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("抖音分享链接", shareText))

        binding.tvShareResult.text = buildString {
            append("✅ 已复制：").append(item.url)
            if (item.desc.isNotBlank() && item.desc != "（来自页面）") {
                append("\n\n描述：").append(item.desc.take(80))
            }
            if (item.author.isNotBlank()) {
                append("\n作者：").append(item.author)
            }
        }
        Toast.makeText(this, "已复制分享口令到剪贴板", Toast.LENGTH_SHORT).show()
        // 自动拉起「抖音精选」（优先）打开该视频；即使拉起失败，
        // 用户手动打开抖音精选时也会自动识别剪贴板口令弹窗。
        openVideoInApp(item.url)
    }

    /** 分享后自动拉起抖音系 App 打开该视频：优先「抖音精选」，其次主抖音，最后浏览器兜底。 */
    private fun openVideoInApp(url: String) {
        // 抖音精选（原青桃视频，中长视频版）/ 主抖音
        val targets = arrayOf("com.ss.android.yumme.video", "com.ss.android.ugc.aweme")
        for (pkg in targets) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (_: Exception) {
                    // 启动失败，尝试下一个
                }
            }
        }
        // 浏览器兜底
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "未找到可打开视频的应用，链接已复制", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 收藏抓取（支持免登录抓取他人公开收藏）
    // ------------------------------------------------------------------

    /** 选择抓取方式：自己的收藏（需登录）或他人的公开收藏（免登录）。 */
    private fun showFetchModeDialog() {
        val options = arrayOf("抓取我的收藏（需登录）", "抓取他人公开收藏（免登录）")
        AlertDialog.Builder(this)
            .setTitle("选择抓取方式")
            .setItems(options) { _, which ->
                if (which == 0) {
                    fetchLauncher.launch(Intent(this, FetchActivity::class.java))
                } else {
                    showGuestInputDialog()
                }
            }
            .show()
    }

    /** 输入对方主页链接 / 用户 ID / 抖音号，免登录抓取公开收藏。 */
    private fun showGuestInputDialog() {
        val input = EditText(this).apply {
            // 默认填入该用户，可直接抓取或改为其他用户
            setText(DEFAULT_GUEST_UID)
            setSelection(text.length)
            hint = "粘贴对方主页链接（douyin.com/user/… 或 v.douyin.com 短链）\n" +
                "或用户 ID / 抖音号"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("抓取他人公开收藏")
            .setMessage("无需登录。仅当对方开启了「公开收藏」时才能抓到数据。")
            .setView(input)
            .setPositiveButton("开始抓取") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "请输入链接 / 用户 ID / 抖音号", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val intent = Intent(this, FetchActivity::class.java)
                    .putExtra(FetchActivity.EXTRA_MODE, FetchActivity.MODE_GUEST)
                    .putExtra(FetchActivity.EXTRA_GUEST_INPUT, text)
                fetchLauncher.launch(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 自动更新
    // ------------------------------------------------------------------

    private fun checkForUpdates(manual: Boolean) {
        updateManager.checkLatest(
            onResult = { hasUpdate, info ->
                if (hasUpdate && info != null) {
                    showUpdateDialog(info)
                } else if (manual) {
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { msg ->
                if (manual) {
                    Toast.makeText(this, "检查更新失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showUpdateDialog(info: UpdateManager.ReleaseInfo) {
        val body = info.body.trim().take(600)
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${info.tag}")
            .setMessage(
                buildString {
                    if (body.isNotEmpty()) {
                        append(body)
                    } else {
                        append("检测到新版本，是否下载更新？")
                    }
                }
            )
            .setPositiveButton("下载更新") { _, _ -> startDownload(info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(info: UpdateManager.ReleaseInfo) {
        showProgressDialog()
        updateManager.downloadAndInstall(info, object : UpdateManager.Listener {
            override fun onDownloadProgress(percent: Int) {
                progressBar?.progress = percent
                progressText?.text = "$percent%"
            }

            override fun onDownloadDone(file: java.io.File) {
                dismissProgressDialog()
                Toast.makeText(this@MainActivity, "下载完成，正在安装…", Toast.LENGTH_SHORT).show()
                updateManager.install(file)
            }

            override fun onDownloadError(message: String) {
                dismissProgressDialog()
                Toast.makeText(this@MainActivity, "下载失败：$message", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun showProgressDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        progressText = TextView(this).apply {
            text = "0%"
            textSize = 13f
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        layout.addView(progressText)
        layout.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        progressDialog = AlertDialog.Builder(this)
            .setTitle("正在下载更新…")
            .setView(layout)
            .setCancelable(false)
            .show()
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressText = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Cookie 导入
    // ------------------------------------------------------------------

    /**
     * 导入 Cookie：粘贴 PC 端 douyin-tools 生成的 douyin_cookies.txt 内容
     * （JSON 格式），或直接粘贴浏览器 Cookie 字符串。
     */
    private fun showImportCookieDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 douyin_cookies.txt 的 JSON 内容\n或 sessionid=xxx; ttwid=yyy 形式的 Cookie"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("导入 Cookie")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                importCookieText(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importCookieText(text: String) {
        val map = LinkedHashMap<String, String>()
        try {
            // 尝试 JSON 格式（douyin-tools 的 douyin_cookies.txt）
            val o = JSONObject(text)
            o.keys().forEach { k -> map[k] = o.optString(k) }
        } catch (_: Exception) {
            // 退化为普通 Cookie 字符串格式
            LoginActivity.parseCookies(text).forEach { (k, v) -> map[k] = v }
        }
        if (map.isEmpty()) {
            Toast.makeText(this, "无法识别的内容", Toast.LENGTH_SHORT).show()
            return
        }
        store.saveCookies(map)
        val msg = if (store.hasSession()) {
            "✅ 已导入 ${map.size} 个 Cookie，登录态有效"
        } else {
            "已导入 ${map.size} 个 Cookie，但未检测到 sessionid，登录态可能不完整"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    companion object {
        /** 访客抓取输入框的默认用户 ID。 */
        private const val DEFAULT_GUEST_UID = "54132528295"
    }
}
