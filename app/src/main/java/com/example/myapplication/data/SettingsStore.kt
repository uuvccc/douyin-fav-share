package com.example.myapplication.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：抖音 Cookie 与收藏列表。
 *
 * 安全说明：Cookie 等同登录态，仅保存在本机应用私有目录，
 * 不会发送到任何第三方。请勿将本应用数据导出/分享。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("dy_tools", Context.MODE_PRIVATE)

    /** 最近一次抓取收藏的时间戳（毫秒）。 */
    var lastUpdatedAt: Long
        get() = prefs.getLong(KEY_UPDATED, 0L)
        set(v) = prefs.edit().putLong(KEY_UPDATED, v).apply()

    fun saveCookies(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_COOKIE, o.toString()).apply()
    }

    fun loadCookies(): Map<String, String> {
        val raw = prefs.getString(KEY_COOKIE, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun hasSession(): Boolean =
        loadCookies().let { it.containsKey("sessionid") && it["sessionid"]!!.isNotBlank() }

    fun saveFavorites(items: List<FavoriteItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    fun loadFavorites(): List<FavoriteItem> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { FavoriteItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY_COOKIE = "dy_cookie_json"
        private const val KEY_FAVORITES = "dy_favorites_json"
        private const val KEY_UPDATED = "dy_last_updated"
    }
}
