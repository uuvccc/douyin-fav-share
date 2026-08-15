package com.example.myapplication.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManagerTest {

    private fun asset(name: String, url: String) =
        JSONObject().put("name", name).put("browser_download_url", url)

    @Test
    fun `buildIdFromTag parses standard build-tag`() {
        assertEquals(31874493132L, UpdateManager.buildIdFromTag("build-31874493132"))
    }

    @Test
    fun `buildIdFromTag extracts all digits from prefixed tag`() {
        // 该函数只保留标签里所有数字字符，不区分前缀
        assertEquals(12342L, UpdateManager.buildIdFromTag("v1.2.3-build-42"))
        assertEquals(7L, UpdateManager.buildIdFromTag("build-7"))
    }

    @Test
    fun `buildIdFromTag returns 0 for tag without digits`() {
        assertEquals(0L, UpdateManager.buildIdFromTag("build"))
        assertEquals(0L, UpdateManager.buildIdFromTag("release"))
    }

    @Test
    fun `buildIdFromTag handles null and empty`() {
        assertEquals(0L, UpdateManager.buildIdFromTag(null))
        assertEquals(0L, UpdateManager.buildIdFromTag(""))
    }

    // ------------------------------------------------------------------
    // pickApkAsset
    // ------------------------------------------------------------------

    @Test
    fun `pickApkAsset prefers release apk over debug`() {
        // Release 资产顺序是 debug 在前、release 在后；必须跳过 debug 选 release
        val assets = JSONArray()
            .put(asset("app-debug.apk", "https://x/app-debug.apk"))
            .put(asset("app-release.apk", "https://x/app-release.apk"))
        val picked = UpdateManager.pickApkAsset(assets)
        assertEquals("app-release.apk", picked?.first)
        assertEquals("https://x/app-release.apk", picked?.second)
    }

    @Test
    fun `pickApkAsset falls back to any apk when no release`() {
        val assets = JSONArray().put(asset("app-debug.apk", "https://x/app-debug.apk"))
        assertEquals("app-debug.apk", UpdateManager.pickApkAsset(assets)?.first)
    }

    @Test
    fun `pickApkAsset ignores non-apk assets`() {
        val assets = JSONArray()
            .put(asset("SHA256SUMS.txt", "https://x/sums"))
            .put(asset("app-release.apk", "https://x/app-release.apk"))
        assertEquals("app-release.apk", UpdateManager.pickApkAsset(assets)?.first)
    }

    @Test
    fun `pickApkAsset returns null for null, empty, or no apk`() {
        assertNull(UpdateManager.pickApkAsset(null))
        assertNull(UpdateManager.pickApkAsset(JSONArray()))
        assertNull(UpdateManager.pickApkAsset(JSONArray().put(asset("readme.md", "https://x/readme"))))
    }
}
