package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginActivityTest {

    @Test
    fun `parseCookies parses key-value pairs`() {
        val map = LoginActivity.parseCookies(
            "sessionid=abc123; ttwid=xyz; path=/; isLogin=true"
        )
        assertEquals("abc123", map["sessionid"])
        assertEquals("xyz", map["ttwid"])
        assertEquals("true", map["isLogin"])
        assertEquals("/", map["path"])
        assertEquals(4, map.size)
    }

    @Test
    fun `parseCookies preserves values containing equals`() {
        val map = LoginActivity.parseCookies("a=b=c; d=e")
        assertEquals("b=c", map["a"])
    }

    @Test
    fun `parseCookies handles leading whitespace`() {
        val map = LoginActivity.parseCookies("  sessionid =  xyz  ; k=v")
        // 键值均被 trim
        assertEquals("xyz", map["sessionid"])
    }

    @Test
    fun `parseCookies returns empty map for empty or malformed input`() {
        assertTrue(LoginActivity.parseCookies("").isEmpty())
        assertTrue(LoginActivity.parseCookies(";;;").isEmpty())
        assertTrue(LoginActivity.parseCookies("no-equals-here").isEmpty())
    }
}
