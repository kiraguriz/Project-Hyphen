package dev.hyphen.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedLineBufferTest {
    @Test
    fun retainsLatestLinesInRenderOrder() {
        val buffer = BoundedLineBuffer(maxLines = 3, initialLine = "header")

        buffer.append("one")
        buffer.append("two")
        buffer.append("three")

        assertEquals(listOf("one", "two", "three"), buffer.snapshot())
        assertEquals("one\ntwo\nthree\n", buffer.render())
    }

    @Test
    fun preservesInitialLineUntilCapacityIsExceeded() {
        val buffer = BoundedLineBuffer(maxLines = 3, initialLine = "header")

        buffer.append("one")
        buffer.append("two")

        assertEquals(listOf("header", "one", "two"), buffer.snapshot())
        assertEquals("header\none\ntwo\n", buffer.render())
    }
}
