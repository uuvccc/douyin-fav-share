package com.example.myapplication.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteItemTest {

    @Test
    fun `toJson emits compatible field names`() {
        val item = FavoriteItem(
            awemeId = "7301234567890123456",
            desc = "一段描述",
            url = "https://www.douyin.com/video/7301234567890123456",
            author = "作者",
            awemeType = 0,
        )
        val json = item.toJson()
        assertEquals("7301234567890123456", json.getString("aweme_id"))
        assertEquals("一段描述", json.getString("desc"))
        assertEquals("https://www.douyin.com/video/7301234567890123456", json.getString("url"))
        assertEquals("作者", json.getString("author"))
        assertEquals(0, json.getInt("aweme_type"))
    }

    @Test
    fun `fromJson round-trips a full item`() {
        val item = FavoriteItem("1", "desc", "url", "author", 2)
        val parsed = FavoriteItem.fromJson(item.toJson())
        assertEquals(item, parsed)
    }

    @Test
    fun `fromJson fills url from id when url missing`() {
        val parsed = FavoriteItem.fromJson(
            JSONObject().put("aweme_id", "7301234567890123456")
        )
        assertEquals("https://www.douyin.com/video/7301234567890123456", parsed.url)
    }

    @Test
    fun `fromJson applies defaults when fields missing`() {
        val parsed = FavoriteItem.fromJson(JSONObject())
        assertEquals("", parsed.awemeId)
        assertEquals("", parsed.desc)
        assertEquals("", parsed.url)
        assertEquals("", parsed.author)
        assertEquals(0, parsed.awemeType)
    }
}
