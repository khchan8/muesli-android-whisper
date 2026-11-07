package com.phequals7.muesli.ui.dashboard.meetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the v2.1 enrollment-clip gate: the UI must always hand
 * SpeakerDiarizer at least the 6 seconds its contract requires, and render
 * cosine similarity (-1..1) as a clamped percentage.
 */
class EnrollmentClipTest {

    @Test
    fun `enrollment clip must be at least 6 seconds`() {
        assertFalse(EnrollmentClip.isUsableEnrollmentClip(EnrollmentClip.MIN_ENROLL_SAMPLES - 1))
        assertTrue(EnrollmentClip.isUsableEnrollmentClip(EnrollmentClip.MIN_ENROLL_SAMPLES))
        assertTrue(EnrollmentClip.isUsableEnrollmentClip(EnrollmentClip.RECORD_TARGET_SAMPLES))
    }

    @Test
    fun `recorder captures 8 seconds at 16 kHz`() {
        assertEquals(96_000, EnrollmentClip.MIN_ENROLL_SAMPLES)
        assertEquals(128_000, EnrollmentClip.RECORD_TARGET_SAMPLES)
        assertEquals(8_000L, EnrollmentClip.RECORD_DURATION_MS)
    }

    @Test
    fun `similarity percent is rounded and clamped to the cosine range`() {
        assertEquals(100, EnrollmentClip.similarityPercent(1f))
        assertEquals(62, EnrollmentClip.similarityPercent(0.623f))
        assertEquals(0, EnrollmentClip.similarityPercent(0f))
        assertEquals(-100, EnrollmentClip.similarityPercent(-1f))
        assertEquals(100, EnrollmentClip.similarityPercent(1.4f))
        assertEquals(-100, EnrollmentClip.similarityPercent(-3f))
    }
}
