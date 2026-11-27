package com.littlecodesauce.ggnext

import com.littlecodesauce.ggnext.core.SelectionState
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {

    @Test
    fun toggleSelectAllInvertClear_areConsistent() {
        var sel = SelectionState.EMPTY
        assertEquals(0, sel.selectedCount(10))
        assertFalse(sel.isSelected(1L))

        sel = sel.toggle(1L).toggle(2L)
        assertEquals(2, sel.selectedCount(10))
        assertTrue(sel.isSelected(1L))
        assertTrue(sel.isSelected(2L))
        assertFalse(sel.isSelected(3L))

        // Invert -> 8 selected out of 10
        sel = sel.invert()
        assertEquals(8, sel.selectedCount(10))
        assertFalse(sel.isSelected(1L))
        assertFalse(sel.isSelected(2L))
        assertTrue(sel.isSelected(3L))

        // Toggle key 1 back on while allSelected = true
        sel = sel.toggle(1L)
        assertEquals(9, sel.selectedCount(10))
        assertTrue(sel.isSelected(1L))

        // SelectAll resets exclusions
        sel = sel.selectAll()
        assertEquals(10, sel.selectedCount(10))
        assertTrue(sel.isSelected(2L))

        // Clear resets everything
        sel = sel.clear()
        assertEquals(0, sel.selectedCount(10))
        assertFalse(sel.hasAnySelection(10))
    }

    @Test
    fun itemKey_distinguishesSameAddressWithDifferentTypes() {
        val dwordItem = MemoryItem(address = 0x1000L, value = 100L, flags = ValueType.DWORD)
        val floatItem = MemoryItem(address = 0x1000L, value = 100L, flags = ValueType.FLOAT)

        val k1 = SelectionState.itemKey(dwordItem)
        val k2 = SelectionState.itemKey(floatItem)
        assertTrue(k1 != k2)

        val sel = SelectionState.EMPTY.toggle(k1)
        assertTrue(sel.isSelected(k1))
        assertFalse(sel.isSelected(k2))
    }

    @Test
    fun prune_removesStaleKeysAndPreservesValidOnes() {
        var sel = SelectionState.EMPTY.toggle(10L).toggle(20L).toggle(30L)
        sel = sel.prune(setOf(20L, 40L))
        assertEquals(1, sel.selectedCount(2))
        assertFalse(sel.isSelected(10L))
        assertTrue(sel.isSelected(20L))
        assertFalse(sel.isSelected(30L))
    }

    @Test
    fun bulk100kItems_selectAllInvertToggle_isO1Fast() {
        val total = 100_000
        val startNs = System.nanoTime()

        var sel = SelectionState.EMPTY.selectAll()
        assertEquals(total, sel.selectedCount(total))

        sel = sel.toggle(42L)
        assertEquals(total - 1, sel.selectedCount(total))
        assertFalse(sel.isSelected(42L))
        assertTrue(sel.isSelected(43L))

        sel = sel.invert()
        assertEquals(1, sel.selectedCount(total))
        assertTrue(sel.isSelected(42L))
        assertFalse(sel.isSelected(43L))

        sel = sel.clear()
        assertEquals(0, sel.selectedCount(total))

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected O(1) operations under 50ms, took ${elapsedMs}ms", elapsedMs < 50L)
    }
}
