package com.example.myapplication.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManagerTest {

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
}
