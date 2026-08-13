package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.SettingsStore
import com.example.myapplication.databinding.ActivityMainBinding
import org.json.JSONObject

/**
 * 主界面：三步完成「自动获取 Cookie -> 抓取收藏 -> 随机分享」。
 *
 * 1. 登录：内嵌 WebView 登录抖音，自动提取 Cookie（也可从 PC 端 douyin-tools
 *    生成的 douyin_cookies.txt 内容粘贴导入）。
 * 2. 抓取：WebView 打开收藏页，自动滚动翻页并捕获收藏列表。
 * 3. 分享：从收藏中随机选一条，复制链接到剪贴板。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    private val fetchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)

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
        binding.btnFetch.setOnClickListener {
            fetchLauncher.launch(Intent(this, FetchActivity::class.java))
        }
        binding.btnShare.setOnClickListener { shareRandom() }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("抖音分享链接", item.url))

        binding.tvShareResult.text = buildString {
            append("✅ 已复制：").append(item.url)
            if (item.desc.isNotBlank() && item.desc != "（来自页面）") {
                append("\n\n描述：").append(item.desc.take(80))
            }
            if (item.author.isNotBlank()) {
                append("\n作者：").append(item.author)
            }
        }
        Toast.makeText(this, "已复制链接到剪贴板", Toast.LENGTH_SHORT).show()
    }

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
        Toast.makeText(this, "已导入 ${map.size} 个 Cookie", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }
}
