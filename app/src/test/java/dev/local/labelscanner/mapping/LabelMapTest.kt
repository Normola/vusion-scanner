package dev.local.labelscanner.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelMapTest {
    private val csv = """
        id,aisle,modular
        # comment
        1H-234567,A3,M2
        w322CFGH,B1
        AA:BB:CC,C7,M9
        AA:BB:CC:DD,C8,M1
        broken-line
    """.trimIndent()

    @Test
    fun `parses link ids and mac prefixes`() {
        val (map, skipped) = LabelMap.parseCsv(csv)
        assertEquals(4, map.size)
        assertEquals(listOf(7), skipped)
        assertEquals(LabelLocation("A3", "M2"), map.lookup("1H234567", null))
        assertEquals(LabelLocation("B1", null), map.lookup("w322-CFGH", null))
        assertEquals(LabelLocation("C8", "M1"), map.lookup(null, "aa:bb:cc:dd:00:11"))
        assertEquals(LabelLocation("C7", "M9"), map.lookup(null, "AA:BB:CC:00:00:11"))
        assertNull(map.lookup("ZZZZZZZZ", "11:22:33:44:55:66"))
    }
}
