package com.example.myapplication.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.example.myapplication.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新管理：检测 GitHub Release 新版本、下载 APK、拉起系统安装器。
 *
 * - 版本来源：GitHub Releases 标签 `build-<run_id>`（CI 每次构建自动递增）。
 * - 版本对比：服务器 build id > 本地 `BuildConfig.CI_BUILD_ID`（CI 构建时注入）
 *   则判定有新版本；本地手动构建时 CI_BUILD_ID=0，仅用于测试。
 * - 下载加速：内置多个公开的 GitHub 下载代理/镜像，逐个尝试直到成功。
 * - 安装：FileProvider + 系统安装器（Android 8.0+ 需用户允许未知来源）。
 */
class UpdateManager(private val appContext: Context) {

    data class ReleaseInfo(
        val buildId: Long,
        val tag: String,
        val assetName: String,
        val assetUrl: String,
        val body: String,
    )

    interface Listener {
        fun onDownloadProgress(percent: Int)
        fun onDownloadDone(file: File)
        fun onDownloadError(message: String)

        /** 下载被用户取消（默认空实现，方便只关心部分回调的调用方）。 */
        fun onDownloadCancelled() {}
    }

    // 下载取消标志：cancelDownload() 置位后，下载线程在下一个读块处终止并清理半成品。
    @Volatile
    private var cancelRequested = false

    /** 取消正在进行的下载。已在下载中调用才有效；幂等，可安全重复调用。 */
    fun cancelDownload() {
        cancelRequested = true
    }

    /** 下载被用户主动取消时抛出，用于区分「取消」与「网络/镜像失败」。 */
    private class DownloadCancelledException : IOException()

    // 公开的 GitHub 代理/镜像（按顺序重试；"" 表示直连）。
    // 失效时可将失效项从列表移除，或自行增补新镜像。
    private val mirrors = listOf(
        "",                                   // 直连
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/",
        "https://gh.llkk.cc/",
        "https://ghps.cc/",
        "https://hub.gitmirror.com/",
    )

    private val main = Handler(Looper.getMainLooper())

    private fun post(r: () -> Unit) = main.post(r)

    // ------------------------------------------------------------------
    // 版本检测
    // ------------------------------------------------------------------

    /**
     * 异步检查最新 Release。
     * [onResult] 返回 true 表示有新版本（附 [ReleaseInfo]）。
     */
    fun checkLatest(onResult: (Boolean, ReleaseInfo?) -> Unit, onError: (String) -> Unit) {
        Thread {
            var lastErr = ""
            for (m in mirrors) {
                try {
                    val url = m + API_URL
                    val info = fetchRelease(url)
                    if (info != null) {
                        val current = BuildConfig.CI_BUILD_ID
                        val hasUpdate = current == 0L || info.buildId > current
                        post { onResult(hasUpdate, info) }
                        return@Thread
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "网络错误"
                }
            }
            post { onError(lastErr.ifBlank { "无法连接更新服务器" }) }
        }.start()
    }

    private fun fetchRelease(apiUrl: String): ReleaseInfo? {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Douyin-Fav-Share-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) return null
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = o.optString("tag_name")
            val buildId = buildIdFromTag(tag)
            if (buildId <= 0L) return null

            val picked = pickApkAsset(o.optJSONArray("assets")) ?: return null
            return ReleaseInfo(buildId, tag, picked.first, picked.second, o.optString("body", ""))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 下载 + 安装
    // ------------------------------------------------------------------

    /** 异步下载并安装 Release 的 APK。 */
    fun downloadAndInstall(info: ReleaseInfo, listener: Listener) {
        cancelRequested = false
        Thread {
            var lastErr = ""
            for (m in mirrors) {
                if (cancelRequested) {
                    post { listener.onDownloadCancelled() }
                    return@Thread
                }
                try {
                    val url = m + info.assetUrl
                    val file = download(url, info.assetName) { pct ->
                        post { listener.onDownloadProgress(pct) }
                    }
                    if (file != null) {
                        post { listener.onDownloadDone(file) }
                        return@Thread
                    }
                } catch (e: DownloadCancelledException) {
                    post { listener.onDownloadCancelled() }
                    return@Thread
                } catch (e: Exception) {
                    lastErr = e.message ?: "下载失败"
                }
            }
            post { listener.onDownloadError(lastErr.ifBlank { "所有镜像均下载失败" }) }
        }.start()
    }

    private fun download(url: String, fileName: String, onProgress: (Int) -> Unit): File? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Douyin-Fav-Share-Android")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
            val target = File(dir, fileName)
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            if (cancelRequested) throw DownloadCancelledException()
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                onProgress(pct)
                            }
                        }
                    }
                }
            } catch (e: DownloadCancelledException) {
                // 清理下载了一半的文件，避免残留占空间
                target.delete()
                throw e
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** 通过系统安装器安装 APK。 */
    fun install(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/uuvccc/douyin-fav-share/releases/latest"

        /**
         * 从 GitHub Release 标签解析 build id（纯函数，便于单元测试）。
         *
         * 标签格式为 `build-<run_id>`，例如 `build-31874493132`；仅取其中的数字部分。
         * 兼容形如 `v1.2.3-build-42` 或 `build-abc-123` 的变体——只保留数字。
         * 解析不出正数时返回 0。
         */
        fun buildIdFromTag(tag: String?): Long =
            tag?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L

        /**
         * 从 GitHub Release 资产里挑出要下载的 APK（纯函数，便于单元测试）。
         *
         * 优先选 Release 构建：CI 用仓库 Secrets 里固定的 release keystore 签名，
         * 签名跨构建稳定，可覆盖安装升级。不能优先 Debug 构建——CI 每次构建都会
         * 重新生成 debug keystore，签名不稳定，覆盖安装旧版会报
         * 「软件包与现有软件包存在冲突」(INSTALL_FAILED_UPDATE_INCOMPATIBLE)。
         *
         * @return `(assetName, browser_download_url)`；没有任何 apk 时返回 null。
         */
        fun pickApkAsset(assets: JSONArray?): Pair<String, String>? {
            if (assets == null) return null
            var fallback: Pair<String, String>? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (!name.endsWith(".apk") || url.isEmpty()) continue
                if (name.contains("release")) return name to url
                if (fallback == null) fallback = name to url
            }
            return fallback
        }
    }
}
