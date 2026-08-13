package com.example.myapplication.data

import org.json.JSONObject

/**
 * 单条收藏视频。
 * 与 PC 端 douyin-tools/favorites.json 的字段保持兼容。
 */
data class FavoriteItem(
    val awemeId: String,
    val desc: String = "",
    val url: String,
    val author: String = "",
    val awemeType: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("aweme_id", awemeId)
        put("desc", desc)
        put("url", url)
        put("author", author)
        put("aweme_type", awemeType)
    }

    companion object {
        fun fromJson(o: JSONObject): FavoriteItem {
            val id = o.optString("aweme_id")
            return FavoriteItem(
                awemeId = id,
                desc = o.optString("desc", ""),
                url = o.optString("url", if (id.isNotEmpty()) "https://www.douyin.com/video/$id" else ""),
                author = o.optString("author", ""),
                awemeType = o.optInt("aweme_type", 0),
            )
        }
    }
}
