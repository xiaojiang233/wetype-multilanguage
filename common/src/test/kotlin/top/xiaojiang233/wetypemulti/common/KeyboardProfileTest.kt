package top.xiaojiang233.wetypemulti.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.xiaojiang233.wetypemulti.common.keyboard.HostBaseLayout
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfileRegistry

class KeyboardProfileTest {

    @Test
    fun testDefaultProfiles() {
        val zh = KeyboardProfileRegistry.findById("zh_CN")
        assertNotNull(zh)
        assertTrue(zh!!.isHostHandled)
        assertEquals(HostBaseLayout.CHINESE_QWERTY, zh.hostLayout)
        assertEquals("test", zh.preeditFormatter.format("test"))
        assertEquals(",", zh.punctuationMapper.map(","))

        val en = KeyboardProfileRegistry.findById("en_US")
        assertNotNull(en)
        assertTrue(en!!.isHostHandled)
        assertEquals(HostBaseLayout.ENGLISH_QWERTY, en.hostLayout)

        val ja = KeyboardProfileRegistry.findById("ja_JP")
        assertNotNull(ja)
        assertFalse(ja!!.isHostHandled)
        assertEquals(HostBaseLayout.CHINESE_QWERTY, ja.hostLayout)
        assertEquals("とうきょう", ja.preeditFormatter.format("toukyou"))
        assertEquals("、", ja.punctuationMapper.map("，"))
        assertEquals("「", ja.punctuationMapper.map("“"))
        assertEquals("」", ja.punctuationMapper.map("”"))
        assertEquals("〜", ja.punctuationMapper.map("～"))
        assertEquals("ー", ja.punctuationMapper.map("-"))
        assertTrue(ja.targetLayoutPaths.isNotEmpty())
        assertTrue(ja.layoutPatches.isNotEmpty())
        assertEquals("shift", ja.overlayKeySlot)
    }

    @Test
    fun testAllProfilesUniqueIds() {
        val all = KeyboardProfileRegistry.ALL
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "All keyboard profile IDs must be unique")
    }
}
