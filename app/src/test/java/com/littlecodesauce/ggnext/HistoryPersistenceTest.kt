package com.littlecodesauce.ggnext

import com.littlecodesauce.ggnext.data.HistoryPersistence
import com.littlecodesauce.ggnext.data.ScriptHistoryEntry
import com.littlecodesauce.ggnext.data.SearchHistory
import com.littlecodesauce.ggnext.data.SearchHistoryEntry
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPersistenceTest {

    @Test
    fun searchHistoryJson_preservesSpecialCharsAndPins() {
        val entries = listOf(
            SearchHistoryEntry(text = "100;200;300::64", pinned = true),
            SearchHistoryEntry(text = "\"hello\\world\"\nline2\t~50", pinned = false),
            SearchHistoryEntry(text = "中文搜索;25X4", pinned = true)
        )
        val encoded = HistoryPersistence.encodeSearchHistory(entries)
        val decoded = HistoryPersistence.decodeSearchHistory(encoded)
        assertEquals(entries, decoded)
    }

    @Test
    fun gotoAndScriptHistoryJson_roundTripWithNotesAndPins() {
        val gotoList = listOf("7A8B0010", "DEADBEEF", "1000")
        val decodedGoto = HistoryPersistence.decodeGotoHistory(HistoryPersistence.encodeGotoHistory(gotoList))
        assertEquals(gotoList, decodedGoto)

        val scripts = listOf(
            ScriptHistoryEntry("/sdcard/Download/a \"quoted\" script.lua", pinned = true, note = "主线脚本\n第二行"),
            ScriptHistoryEntry("/sdcard/GGNext/test\\path.lua", pinned = false, note = "")
        )
        val decodedScripts = HistoryPersistence.decodeScriptHistory(HistoryPersistence.encodeScriptHistory(scripts))
        assertEquals(scripts, decodedScripts)
    }

    @Test
    fun savedListJson_preservesFreezeModesAndXorMetadata() {
        val items = listOf(
            MemoryItem(
                address = 0x71234560L,
                value = 9999L,
                previousValue = 100L,
                flags = ValueType.DWORD,
                name = "HP",
                region = "Ca",
                freeze = true,
                freezeType = 3,
                freezeFrom = 100L,
                freezeTo = 9999L,
                note = "血量 \"锁定\"\n测试",
                xorKey = 0x12345678L,
                xorOffset = 4,
                encryptedAuto = true
            )
        )
        val decoded = HistoryPersistence.decodeSavedList(HistoryPersistence.encodeSavedList(items))
        assertEquals(1, decoded.size)
        val item = decoded.first()
        assertEquals(0x71234560L, item.address)
        assertEquals(9999L, item.value)
        assertEquals(100L, item.previousValue)
        assertEquals(ValueType.DWORD, item.flags)
        assertEquals("HP", item.name)
        assertEquals("Ca", item.region)
        assertTrue(item.freeze)
        assertEquals(3, item.freezeType)
        assertEquals(100L, item.freezeFrom)
        assertEquals(9999L, item.freezeTo)
        assertEquals("血量 \"锁定\"\n测试", item.note)
        assertEquals(0x12345678L, item.xorKey)
        assertEquals(4, item.xorOffset)
        assertTrue(item.encryptedAuto)
    }

    @Test
    fun searchHistory_respectsLimitZeroAndProtectsPinnedEntries() {
        val h = SearchHistory(initialLimit = 3)
        assertTrue(h.add("A"))
        assertTrue(h.add("B"))
        assertTrue(h.add("C"))
        h.pin("A", true)

        // Adding D should evict oldest unpinned ("B"), NOT pinned "A"
        assertTrue(h.add("D"))
        val list = h.list()
        assertEquals(3, list.size)
        assertTrue(list.contains("A"))
        assertTrue(list.contains("C"))
        assertTrue(list.contains("D"))
        assertFalse(list.contains("B"))

        // limit = 0 forbids new entries and strips unpinned ones while preserving pinned "A"
        h.setLimit(0)
        assertFalse(h.add("E"))
        assertEquals(listOf("A"), h.list())
    }

    @Test
    fun trimScriptHistory_neverEvictsPinnedEntries() {
        val list = listOf(
            ScriptHistoryEntry("/s1.lua", pinned = false),
            ScriptHistoryEntry("/s2.lua", pinned = true),
            ScriptHistoryEntry("/s3.lua", pinned = false),
            ScriptHistoryEntry("/s4.lua", pinned = true)
        )
        val trimmed = HistoryPersistence.trimScriptHistory(list, limit = 2)
        assertEquals(2, trimmed.size)
        assertTrue(trimmed.all { it.pinned })

        val zeroLimit = HistoryPersistence.trimScriptHistory(list, limit = 0)
        assertEquals(2, zeroLimit.size)
        assertTrue(zeroLimit.all { it.pinned })
    }
}
