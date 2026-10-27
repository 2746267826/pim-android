package com.pim.app.recording

import com.pim.app.TestPimApp
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class RecordingEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = RecordingFileStore(folder.root)
    private fun tinyPolicy() = RecordingPolicy(chunkSamples = 6, segmentSamples = 10, checkpointSamples = 2)

    @Test fun actualSamplesSplitAcrossChunkAndUserSegmentBoundariesWithoutLoss() {
        val store = store()
        val engine = RecordingEngine(store, tinyPolicy(), 100)
        val input = ShortArray(53) { (it - 26).toShort() }
        engine.append(input, input.size)
        engine.finish()
        assertEquals(RecordingPhase.Completed, engine.session.phase)
        assertEquals(6, engine.session.currentSegment)
        assertEquals(53L, engine.session.samples)
        assertEquals(listOf(6L, 4, 6, 4, 6, 4, 6, 4, 6, 4, 3), engine.session.chunks.map { it.samples })
        val payload = engine.session.chunks.flatMap { chunk ->
            val bytes = store.audioFile(engine.session.id, chunk).readBytes().drop(44).toByteArray()
            val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            List(values.remaining()) { values.get() }
        }.toShortArray()
        assertArrayEquals(input, payload)
    }

    @Test fun pauseAddsNoTimeAndResumeKeepsTheSameUserSegment() {
        val engine = RecordingEngine(store())
        engine.append(ShortArray(16_000), 16_000)
        engine.pause()
        assertEquals(1000L, engine.session.durationMillis)
        assertThrows(IllegalStateException::class.java) { engine.append(shortArrayOf(1), 1) }
        engine.resume()
        engine.append(ShortArray(8000), 8000)
        engine.finish()
        assertEquals(1500L, engine.session.durationMillis)
        assertEquals(1, engine.session.currentSegment)
        assertEquals(2, engine.session.chunks.size)
    }

    @Test fun waveformUsesInputPeakIncludingNegativeFullScaleNotDecorativeData() {
        val engine = RecordingEngine(store())
        assertEquals(0f, engine.append(ShortArray(10), 10), 0f)
        assertEquals(1f, engine.append(shortArrayOf(Short.MIN_VALUE), 1), 0f)
        assertEquals(0.5f, engine.append(shortArrayOf(16_384), 1), 0f)
        engine.finish()
    }

    @Test fun productionPolicyIsThreeHoursPerUserSegmentNotPerSession() {
        val policy = RecordingPolicy()
        assertEquals(172_800_000L, policy.segmentSamples)
        assertEquals(960_000L, policy.chunkSamples)
        assertEquals(16_000L, policy.checkpointSamples)
        val engine = RecordingEngine(store(), tinyPolicy())
        repeat(100) { engine.append(ShortArray(10), 10) }
        engine.finish()
        assertEquals(100, engine.session.currentSegment)
        assertEquals(1000L, engine.session.samples)
    }

    @Test fun emptyOrFailedInputCannotBecomeSuccessfulSavedRecording() {
        val empty = RecordingEngine(store())
        empty.finish()
        assertEquals(RecordingProblem.Empty, empty.session.problem)
        assertEquals(RecordingPhase.Interrupted, empty.session.phase)
        val failed = RecordingEngine(store())
        failed.append(shortArrayOf(1, 2), 2)
        failed.finish(RecordingProblem.Input)
        assertEquals(RecordingPhase.Interrupted, failed.session.phase)
        assertEquals(2L, failed.session.samples)
    }

    @Test fun recoveryRepairsStaleWavHeaderFromActualPayloadAndDoesNotRestartCapture() {
        val store = store()
        val engine = RecordingEngine(store())
        engine.append(shortArrayOf(100, 200, 300), 3)
        engine.pause()
        val session = engine.session
        val audio = store.audioFile(session.id, session.chunks.single())
        audio.appendBytes(byteArrayOf(0, 1, 0, 2))
        val recovered = store.archives(recover = true).single().session!!
        assertEquals(5L, recovered.samples)
        assertEquals(RecordingPhase.Interrupted, recovered.phase)
        assertEquals(RecordingProblem.ProcessEnded, recovered.problem)
        assertEquals(10, ByteBuffer.wrap(audio.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertFalse(recovered.active)
    }

    @Test fun orphanChunkCreatedBeforeManifestCommitIsRecoveredInItsOriginalSegment() {
        val store = store()
        val session = store.create(1)
        val orphan = store.audioFile(session.id, RecordingChunk(2, 1))
        PcmWavFile(orphan).use { it.append(shortArrayOf(1, 2, 3), 0, 3) }
        val recovered = store.archives(true).single().session!!
        assertEquals(2, recovered.currentSegment)
        assertEquals(3L, recovered.samples)
        assertEquals(RecordingPhase.Interrupted, recovered.phase)
    }

    @Test fun missingFilesRemainExplicitlyDamagedAcrossRepeatedRecovery() {
        val store = store()
        val engine = RecordingEngine(store())
        engine.append(shortArrayOf(1, 2, 3), 3)
        engine.finish()
        assertTrue(store.audioFile(engine.session.id, engine.session.chunks.single()).delete())
        repeat(2) {
            val archive = store.archives(true).single()
            assertTrue(archive.damaged)
            val recovered = requireNotNull(archive.session)
            assertEquals(RecordingProblem.Damaged, recovered.problem)
            assertEquals(1, recovered.chunks.size)
            assertTrue(store.archives(recover = false).single().damaged)
        }
    }

    @Test fun corruptMetadataRemainsVisibleAndNeverDeletesRawAudio() {
        val store = store()
        val engine = RecordingEngine(store())
        engine.append(shortArrayOf(1, 2, 3), 3)
        engine.finish()
        val file = store.audioFile(engine.session.id, engine.session.chunks.single())
        val before = file.readBytes()
        File(file.parentFile, "manifest.json").writeText("not valid JSON")
        val archive = store.archives(true).single()
        assertTrue(archive.damaged)
        assertNull(archive.session)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun corruptWavIsNotRelabeledCompleteOrSilentlyReplaced() {
        val store = store()
        val engine = RecordingEngine(store())
        engine.append(shortArrayOf(1, 2, 3), 3)
        engine.finish()
        val file = store.audioFile(engine.session.id, engine.session.chunks.single())
        file.writeBytes(byteArrayOf(1, 2, 3))
        val archive = store.archives(true).single()
        assertTrue(archive.damaged)
        assertEquals(RecordingPhase.Interrupted, archive.session!!.phase)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test fun fileIdentityCannotEscapePrivateRecordingDirectoryOrOverwriteExistingAudio() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.audioFile("../outside", RecordingChunk(1, 1)) }
        val session = store.create(1)
        val file = store.audioFile(session.id, RecordingChunk(1, 1))
        PcmWavFile(file).use { it.append(shortArrayOf(1), 0, 1) }
        val before = file.readBytes()
        assertThrows(IllegalStateException::class.java) { PcmWavFile(file) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun atomicBackupRestoresManifestAndKeepsSamples() {
        val store = store()
        val engine = RecordingEngine(store())
        engine.append(shortArrayOf(1, 2, 3), 3)
        engine.finish()
        val base = File(folder.root, "${engine.session.id}/manifest.json")
        File(base.parentFile, "manifest.json.bak").writeBytes(base.readBytes())
        base.writeText("incomplete replacement")
        val archive = store.archives(true).single()
        assertFalse(archive.damaged)
        assertEquals(3L, archive.session!!.samples)
    }
}
