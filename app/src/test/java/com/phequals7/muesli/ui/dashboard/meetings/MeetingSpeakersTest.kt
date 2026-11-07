package com.phequals7.muesli.ui.dashboard.meetings

import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingSpeakersTest {
    private fun seg(order: Int, start: Long, end: Long, cluster: Int, label: String, profile: String? = null, centroid: FloatArray = FloatArray(0)) =
        DiarizedTranscriptSegment(
            sessionId = "s", orderIndex = order, startMs = start, endMs = end, clusterId = cluster,
            speakerProfileId = profile, speakerLabel = label, badgeColorHex = "#1D63B8", text = "t", clusterCentroid = centroid,
        )

    @Test
    fun groupsTurnsByClusterInFirstAppearanceOrder() {
        val c = floatArrayOf(1f, 0f)
        val speakers = meetingSpeakers(
            listOf(
                seg(0, 0, 1000, 2, "Speaker 1"),
                seg(1, 1000, 4000, 5, "Alice", profile = "p1", centroid = c),
                seg(2, 4000, 6000, 2, "Speaker 1", centroid = floatArrayOf(0f, 1f)),
            )
        )
        assertEquals(listOf(2, 5), speakers.map { it.clusterId })
        assertEquals(2, speakers[0].turns)
        assertEquals(3000L, speakers[0].speakingMs)
        // First stored centroid wins even when it is not on the first turn.
        assertArrayEquals(floatArrayOf(0f, 1f), speakers[0].centroid, 0f)
        assertNull(speakers[0].profileId)
        assertEquals("p1", speakers[1].profileId)
        assertEquals("Alice", speakers[1].label)
    }

    @Test
    fun clusterWithoutStoredCentroidHasEmptyVector() {
        assertEquals(0, meetingSpeakers(listOf(seg(0, 0, 500, 0, "Speaker 1"))).single().centroid.size)
    }
}
