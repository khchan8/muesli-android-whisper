package com.phequals7.muesli.meetings

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingTitleTest {
    @Test
    fun `generated title preserves original and replaces previous generated title`() {
        assertEquals("Team meeting - Roadmap", combinedMeetingTitle("Team meeting", "Team meeting", "Roadmap"))
        assertEquals("Team meeting - Budget", combinedMeetingTitle("Team meeting", "Team meeting - Roadmap", "Budget"))
    }

    @Test
    fun `fallback or duplicate titles do not add another suffix`() {
        assertEquals("Team - Roadmap", combinedMeetingTitle("Team", "Team - Roadmap", "Team - Roadmap"))
        assertEquals("Team - Roadmap", combinedMeetingTitle("Team", "Team - Roadmap", "Team"))
        assertEquals("Team", combinedMeetingTitle("Team", "Team", " "))
    }
}
