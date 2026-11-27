package com.littlecodesauce.ggnext

import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    @Test
    fun parsePlainDecimal() {
        val q = NumberParser.parse("1234", ValueType.DWORD)
        assertEquals(NumberParser.Kind.NUMBER, q.kind)
        assertEquals(1234L, q.numbers[0])
        assertEquals(ValueType.DWORD, q.types[0])
    }

    @Test
    fun parseHexSuffixAndPrefix() {
        val a = NumberParser.parse("1F4h", ValueType.DWORD)
        val b = NumberParser.parse("0x1F4", ValueType.DWORD)
        val c = NumberParser.parse("h1F4", ValueType.DWORD)
        assertEquals(500L, a.numbers[0])
        assertEquals(500L, b.numbers[0])
        assertEquals(500L, c.numbers[0])
    }

    @Test
    fun parseFloatBits() {
        val q = NumberParser.parse("12.5", ValueType.FLOAT)
        assertEquals(12.5f, Float.fromBits(q.numbers[0].toInt()), 0.0001f)
    }

    @Test
    fun parseRangeAndInvertedRange() {
        val r = NumberParser.parse("10~20", ValueType.DWORD)
        assertEquals(NumberParser.Kind.RANGE, r.kind)
        assertEquals(10L, r.numbers[0])
        assertEquals(20L, r.to[0])
        assertEquals(ValueType.SIGN_EQUAL, r.sign)

        val inv = NumberParser.parse("10~~20", ValueType.DWORD)
        assertEquals(NumberParser.Kind.RANGE, inv.kind)
        assertTrue(inv.notRange)
        assertEquals(10L, inv.numbers[0])
        assertEquals(20L, inv.to[0])
    }

    @Test
    fun parseGroupAndOrderedGroup() {
        val g = NumberParser.parse("1;2;3:128", ValueType.DWORD)
        assertEquals(NumberParser.Kind.GROUP, g.kind)
        assertEquals(3, g.numbers.size)
        assertEquals(128, g.area)
        assertEquals(false, g.ordered)

        val og = NumberParser.parse("10W;20D::64", ValueType.AUTO)
        assertEquals(true, og.ordered)
        assertEquals(64, og.area)
        assertEquals(ValueType.WORD, og.types[0])
        assertEquals(ValueType.DWORD, og.types[1])
    }

    @Test
    fun parseTextUtf8AndUtf16() {
        val u8 = NumberParser.parse("'AB", ValueType.AUTO)
        assertEquals(NumberParser.Kind.TEXT, u8.kind)
        assertEquals(2, u8.textBytes.size)

        val u16 = NumberParser.parse("\"AB", ValueType.AUTO)
        assertEquals(4, u16.textBytes.size)
    }

    @Test
    fun parseXorQuery() {
        val off = NumberParser.parse("100X8", ValueType.DWORD)
        assertEquals(100L, off.numbers[0])
        assertEquals(8, off.xorOffset)
        assertEquals(0L, off.xorKey)

        val key = NumberParser.parse("100X8h", ValueType.DWORD)
        assertEquals(100L, key.numbers[0])
        assertEquals(8L, key.xorKey)
        assertEquals(0, key.xorOffset)
    }

    @Test
    fun formatMaskRender() {
        val item = MemoryItem(address = 0x79F1497F24L, value = 100L, flags = ValueType.DWORD)
        val text = Formatter.render(item, Formatter.F_HEX or Formatter.F_DWORD)
        assertTrue(text.contains("00000064h"))
        assertTrue(text.contains("100D"))
        assertEquals("79F1497F24", item.cachedAddressHex)
    }

    @Test
    fun compareTypedValuesOrdering() {
        val engine = MemoryEngine()
        assertEquals(-1, engine.compareTypedValues(-5L, 10L, ValueType.DWORD))
        assertEquals(0, engine.compareTypedValues(3.5f.toRawBits().toLong(), 3.5f.toRawBits().toLong(), ValueType.FLOAT))
        assertTrue(engine.compareTypedValues(10.0f.toRawBits().toLong(), 2.0f.toRawBits().toLong(), ValueType.FLOAT) > 0)
    }
}
