package com.pim.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLiveUpdateCapabilityTest {
    @Test fun `API below 36 is unsupported without a full SDK check`() {
        var checked = false
        assertFalse(RecordingLiveUpdateCapability.supportsLiveUpdates(35) {
            checked = true
            true
        })
        assertFalse(checked)
    }

    @Test fun `API 36 requires the full SDK check`() {
        assertFalse(RecordingLiveUpdateCapability.supportsLiveUpdates(36) { false })
        assertTrue(RecordingLiveUpdateCapability.supportsLiveUpdates(36) { true })
    }

    @Test fun `API 36 missing promotion API falls back`() {
        assertFalse(RecordingLiveUpdateCapability.supportsLiveUpdates(36) {
            throw NoSuchMethodError("setRequestPromotedOngoing")
        })
    }

    @Test fun `newer API remains supported without a full SDK check`() {
        var checked = false
        assertTrue(RecordingLiveUpdateCapability.supportsLiveUpdates(37) {
            checked = true
            false
        })
        assertFalse(checked)
    }
}
