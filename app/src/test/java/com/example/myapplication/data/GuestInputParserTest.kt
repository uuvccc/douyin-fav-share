package com.example.myapplication.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestInputParserTest {

    // ------------------------------------------------------------------
    // extractSecUid
    // ------------------------------------------------------------------

    @Test
    fun `extractSecUid from homepage link`() {
        val secUid = "MS4wLjABAAAA1234567890abcdef"
        assertEquals(secUid, GuestInputParser.extractSecUid("https://www.douyin.com/user/$secUid"))
        assertEquals(secUid, GuestInputParser.extractSecUid("http://douyin.com/user/$secUid"))
    }

    @Test
    fun `extractSecUid returns null for self link`() {
        // user/self 是「我的主页」，不是他人 sec_uid
        assertNull(GuestInputParser.extractSecUid("https://www.douyin.com/user/self"))
    }

    @Test
    fun `extractSecUid recognizes MS4wLjAB prefixed sec_uid`() {
        assertEquals(
            "MS4wLjABAAAA1234567890abcdef",
            GuestInputParser.extractSecUid("MS4wLjABAAAA1234567890abcdef")
        )
    }

    @Test
    fun `extractSecUid recognizes long alphanumeric sec_uid`() {
        val secUid = "aVeryLongSecUidValueWithLengthOver24"
        assertEquals(secUid, GuestInputParser.extractSecUid(secUid))
    }

    @Test
    fun `extractSecUid returns null for pure numeric douyin id`() {
        // 纯数字是 uid/抖音号，不是 sec_uid，/user/{id} 不接受，必须走搜索页
        assertNull(GuestInputParser.extractSecUid("54132528295"))
    }

    @Test
    fun `extractSecUid returns null for short handle and empty`() {
        assertNull(GuestInputParser.extractSecUid(""))
        assertNull(GuestInputParser.extractSecUid("abc"))
    }

    // ------------------------------------------------------------------
    // secUidFromUrl
    // ------------------------------------------------------------------

    @Test
    fun `secUidFromUrl extracts from user url`() {
        val secUid = "MS4wLjABAAAA1234567890abcdef"
        assertEquals(secUid, GuestInputParser.secUidFromUrl("https://www.douyin.com/user/$secUid?showTab=favorite_collection"))
    }

    @Test
    fun `secUidFromUrl returns null for self or non-user url`() {
        assertNull(GuestInputParser.secUidFromUrl("https://www.douyin.com/user/self"))
        assertNull(GuestInputParser.secUidFromUrl("https://www.douyin.com/"))
        assertNull(GuestInputParser.secUidFromUrl(null))
        assertNull(GuestInputParser.secUidFromUrl("https://www.douyin.com/search/foo"))
    }

    // ------------------------------------------------------------------
    // secUidFromDomValue
    // ------------------------------------------------------------------

    @Test
    fun `secUidFromDomValue extracts secUid field`() {
        val value = "{\"result\":{\"secUid\":\"MS4wLjABAAAAabc\",\"nickname\":\"x\"}}"
        assertEquals("MS4wLjABAAAAabc", GuestInputParser.secUidFromDomValue(value))
    }

    @Test
    fun `secUidFromDomValue returns null when no secUid`() {
        assertNull(GuestInputParser.secUidFromDomValue("{\"a\":1}"))
        assertNull(GuestInputParser.secUidFromDomValue(""))
        assertNull(GuestInputParser.secUidFromDomValue(null))
    }
}
