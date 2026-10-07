package com.xyether.handbrake

import org.junit.Assert.*
import org.junit.Test

class EncoderTuningTest {
    @Test fun mapsFullAdvertisedRangeIncludingNonzeroMinimum() {
        assertEquals(3, complexityValue(0f, 3, 13))
        assertEquals(8, complexityValue(.5f, 3, 13))
        assertEquals(13, complexityValue(1f, 3, 13))
    }
    @Test fun rejectsFixedRangesAndInvalidEffort() {
        assertThrows(IllegalArgumentException::class.java) { complexityValue(.5f, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { complexityValue(Float.NaN, 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { complexityValue(1.1f, 0, 10) }
    }
}
