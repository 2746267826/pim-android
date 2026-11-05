package com.pim.app.recording

import android.Manifest
import android.app.Activity
import com.pim.app.TestPimApp
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class RecordingStoragePolicyTest {
    @Test fun startAndContinueBudgetsKeepTheirExactBoundaries() {
        assertFalse(RecordingStoragePolicy.canStart(RecordingStoragePolicy.MIN_START_BYTES - 1))
        assertTrue(RecordingStoragePolicy.canStart(RecordingStoragePolicy.MIN_START_BYTES))
        assertFalse(RecordingStoragePolicy.canContinue(RecordingStoragePolicy.MIN_CONTINUE_BYTES - 1))
        assertTrue(RecordingStoragePolicy.canContinue(RecordingStoragePolicy.MIN_CONTINUE_BYTES))
    }

    @Test fun permissionAndStorageFailuresRemainDistinct() {
        assertEquals(RecordingProblem.Permission, RecordingStoragePolicy.problemFor(SecurityException("denied")))
        assertEquals(RecordingProblem.Storage, RecordingStoragePolicy.problemFor(IOException("full")))
        assertEquals(RecordingProblem.Input, RecordingStoragePolicy.problemFor(IllegalStateException("capture")))
    }

    @Test fun lowSpaceDoesNotStartTheRecordingService() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertEquals(
            RecordingProblem.Storage,
            RecordingController.start(
                activity,
                availableBytes = RecordingStoragePolicy.MIN_START_BYTES - 1,
            ),
        )
        assertNull(shadowOf(activity.application).nextStartedService)
    }
}
