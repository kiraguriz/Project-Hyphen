package dev.hyphen.android

import org.junit.Assert.assertEquals
import org.junit.Test

// Proves the unit-test pipeline runs; real tests start with HYP-M1-002.
class SkeletonSmokeTest {
    @Test
    fun buildPipelineRuns() {
        assertEquals(4, 2 + 2)
    }
}
