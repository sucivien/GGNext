package com.littlecodesauce.ggnext

import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.data.LocalBlobStore
import com.littlecodesauce.ggnext.data.SavedListFileCodec
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.engine.RootMemDaemon
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.components.formatNavBadgeCount
import com.littlecodesauce.ggnext.ui.theme.AppFontCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class FileCodecAndParserTest {

    @Test
    fun savedListFileCodec_roundTripAndLegacyCompatibility() {
        val items = listOf(
            MemoryItem(
                address = 0x70001000L,
                value = 12345L,
                flags = ValueType.DWORD,
                region = "A",
                freeze = true,
                freezeType = 3,
                freezeFrom = 100L,
                freezeTo = 50000L,
                name = "Gold",
                note = "金币",
                xorKey = 0x5A5AL,
                xorOffset = 4,
                encryptedAuto = false
            ),
            MemoryItem(
                address = 0x70001000L,
                value = java.lang.Float.floatToRawIntBits(3.5f).toLong() and 0xFFFFFFFFL,
                flags = ValueType.FLOAT,
                region = "A",
                freeze = false,
                name = "Speed",
                note = "速度"
            )
        )

        val text = SavedListFileCodec.formatToText(items)
        val parsed = SavedListFileCodec.parseFromText(text)
        assertEquals(2, parsed.size)
        assertEquals(0x70001000L, parsed[0].address)
        assertEquals(12345L, parsed[0].value)
        assertEquals(3, parsed[0].freezeType)
        assertEquals(100L, parsed[0].freezeFrom)
        assertEquals(50000L, parsed[0].freezeTo)
        assertEquals(0x5A5AL, parsed[0].xorKey)
        assertEquals(4, parsed[0].xorOffset)
        assertEquals(ValueType.FLOAT, parsed[1].flags)

        // Legacy 8-column format + comments + malformed lines
        val legacy = """
            # comment line
            bad_line_without_pipes
            not_hex|100|4|Ca|0|0|bad|bad
            0x71002000|42|4|Ca|1|0|HP|legacy note
            71002000|42|4|Ca|1|0|HP|duplicate should dedup
        """.trimIndent()
        val parsedLegacy = SavedListFileCodec.parseFromText(legacy)
        assertEquals(1, parsedLegacy.size)
        assertEquals(0x71002000L, parsedLegacy[0].address)
        assertEquals(42L, parsedLegacy[0].value)
        assertTrue(parsedLegacy[0].freeze)
        assertEquals("legacy note", parsedLegacy[0].note)
    }

    @Test
    fun numberParser_parsesExactRangeGroupXorAndText() {
        val qExact = NumberParser.parse("1234", ValueType.DWORD)
        assertEquals(NumberParser.Kind.NUMBER, qExact.kind)
        assertEquals(listOf(1234L), qExact.numbers)
        assertEquals(listOf(ValueType.DWORD), qExact.types)

        val qNotEq = NumberParser.parse("!=500", ValueType.DWORD)
        assertEquals(ValueType.SIGN_NOT_EQUAL, qNotEq.sign)
        assertEquals(listOf(500L), qNotEq.numbers)

        val qRange = NumberParser.parse("10~~20", ValueType.DWORD)
        assertEquals(NumberParser.Kind.RANGE, qRange.kind)
        assertTrue(qRange.notRange)
        assertEquals(listOf(10L), qRange.numbers)
        assertEquals(listOf(20L), qRange.to)

        val qGroup = NumberParser.parse("100D;200F::64", ValueType.AUTO)
        assertEquals(NumberParser.Kind.GROUP, qGroup.kind)
        assertTrue(qGroup.ordered)
        assertEquals(64, qGroup.area)
        assertEquals(2, qGroup.numbers.size)
        assertEquals(ValueType.DWORD, qGroup.types[0])
        assertEquals(ValueType.FLOAT, qGroup.types[1])

        val qXor = NumberParser.parse("25X4", ValueType.DWORD)
        assertEquals(4, qXor.xorOffset)
        assertEquals(listOf(25L), qXor.numbers)

        val qText = NumberParser.parse(":hello", ValueType.AUTO)
        assertEquals(NumberParser.Kind.TEXT, qText.kind)
        assertEquals("hello", String(qText.textBytes, Charsets.UTF_8))
    }

    @Test
    fun appStrings_switchesBetweenChineseAndEnglish() {
        try {
            AppStrings.setLang("0")
            assertEquals("搜索", AppStrings.tr("搜索"))

            AppStrings.setLang("1")
            val enSearch = AppStrings.tr("搜索")
            assertNotEquals("搜索", enSearch)
            assertTrue(enSearch.equals("Search", ignoreCase = true))

            // NumberParser error should be localized in English mode
            try {
                NumberParser.parse("   ", ValueType.DWORD)
                fail("Expected ParseException for blank input")
            } catch (e: NumberParser.ParseException) {
                assertNotEquals("请输入要搜索的值", e.message)
            }
        } finally {
            AppStrings.setLang("0")
        }
    }

    @Test
    fun formatNavBadgeCount_formatsCompactLabels() {
        assertEquals("", formatNavBadgeCount(0))
        assertEquals("42", formatNavBadgeCount(42))
        assertEquals("999", formatNavBadgeCount(999))
        assertEquals("1k", formatNavBadgeCount(1000))
        assertEquals("2.4k", formatNavBadgeCount(2450))
        assertEquals("9.9k", formatNavBadgeCount(9999))
        assertEquals("13k", formatNavBadgeCount(13876))
        assertEquals("99k+", formatNavBadgeCount(100000))
    }

    @Test
    fun rootMemDaemon_fastLineParsersHandle64BitAndNegativeValues() {
        val c1 = RootMemDaemon.parseCandidateLine("7f12345678 -42 1048578")
        assertNotNull(c1)
        assertEquals(0x7f12345678L, c1!!.address)
        assertEquals(-42L, c1.rawValue)
        assertEquals(1048578, c1.meta)

        val highBitAddr = java.lang.Long.parseUnsignedLong("b400007a12345678", 16)
        val c2 = RootMemDaemon.parseCandidateLine("b400007a12345678 ${Long.MIN_VALUE} 4294967295")
        assertNotNull(c2)
        assertEquals(highBitAddr, c2!!.address)
        assertEquals(Long.MIN_VALUE, c2.rawValue)
        assertEquals(-1, c2.meta)

        assertNull(RootMemDaemon.parseCandidateLine("bad"))

        val batchOut = HashMap<Long, Long>()
        RootMemDaemon.parseBatchReadLine("70001000 1 99999", batchOut)
        RootMemDaemon.parseBatchReadLine("70001004 0 12345", batchOut)
        assertEquals(1, batchOut.size)
        assertEquals(99999L, batchOut[0x70001000L])
    }

    @Test
    fun localBlobStore_atomicWriteAndReadRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ggnext_blob_test_${System.nanoTime()}")
        try {
            assertTrue(LocalBlobStore.writeTextAtomic(dir, "saved_list_json", "[{\"address\":123}]"))
            assertEquals("[{\"address\":123}]", LocalBlobStore.readText(dir, "saved_list_json"))
            assertNull(LocalBlobStore.readText(dir, "non_existent_key"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appFontCache_validatesFontMagicHeaders() {
        assertTrue(AppFontCache.isValidFontHeader(byteArrayOf(0x00, 0x01, 0x00, 0x00)))
        assertTrue(AppFontCache.isValidFontHeader("true".toByteArray()))
        assertTrue(AppFontCache.isValidFontHeader("OTTO".toByteArray()))
        assertTrue(AppFontCache.isValidFontHeader("ttcf".toByteArray()))
        assertFalse(AppFontCache.isValidFontHeader("PNG!".toByteArray()))
        assertFalse(AppFontCache.isValidFontHeader(byteArrayOf(0x00, 0x01)))
    }
}
