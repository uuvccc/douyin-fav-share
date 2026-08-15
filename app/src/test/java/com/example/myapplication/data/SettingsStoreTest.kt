package com.example.myapplication.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// targetSdk=37 超出 Robolectric 4.13 支持的 maxSdk(34)，显式固定测试用 SDK
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private lateinit var store: SettingsStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 清空数据，保证测试隔离
        context.getSharedPreferences("dy_tools", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SettingsStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("dy_tools", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `save and load cookies round-trips`() {
        store.saveCookies(mapOf("sessionid" to "abc", "ttwid" to "xyz"))
        val loaded = store.loadCookies()
        assertEquals("abc", loaded["sessionid"])
        assertEquals("xyz", loaded["ttwid"])
    }

    @Test
    fun `loadCookies returns empty map when nothing saved`() {
        assertTrue(store.loadCookies().isEmpty())
    }

    @Test
    fun `hasSession reflects sessionid presence`() {
        assertFalse(store.hasSession())
        store.saveCookies(mapOf("sessionid" to "abc"))
        assertTrue(store.hasSession())
    }

    @Test
    fun `hasSession false when sessionid blank`() {
        store.saveCookies(mapOf("sessionid" to "  "))
        assertFalse(store.hasSession())
    }

    @Test
    fun `save and load favorites round-trips`() {
        val items = listOf(
            FavoriteItem("1", "a", "u1", "author", 0),
            FavoriteItem("2", "b", "u2", "", 1),
        )
        store.saveFavorites(items)
        assertEquals(items, store.loadFavorites())
    }

    @Test
    fun `loadFavorites returns empty list when nothing saved`() {
        assertTrue(store.loadFavorites().isEmpty())
    }

    @Test
    fun `lastUpdatedAt persists`() {
        assertEquals(0L, store.lastUpdatedAt)
        store.lastUpdatedAt = 1700000000000L
        assertEquals(1700000000000L, store.lastUpdatedAt)
    }
}
