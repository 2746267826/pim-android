package com.pim.app.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import java.io.Closeable
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
internal class AndroidPcmInput(private val onSilenced: () -> Unit) : Closeable {
    private val recorder: AudioRecord
    private var callback: AudioManager.AudioRecordingCallback? = null
    @Volatile private var closed = false

    init {
        val minimum = AudioRecord.getMinBufferSize(
            RecordingPolicy.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0)
        recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RecordingPolicy.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, RecordingPolicy.SAMPLE_RATE * 2))
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone initialization failed")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val observer = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (!closed && runCatching { recorder.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)) onSilenced()
                }
            }
            try {
                recorder.registerAudioRecordingCallback(Executor { task -> task.run() }, observer)
                callback = observer
            } catch (error: Exception) { recorder.release(); throw error }
        }
    }

    fun start() {
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        if (Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) onSilenced()
    }

    fun pause() { recorder.stop() }
    // Keep cancellation and the service's no-input timeout independent of a stalled audio source.
    fun read(buffer: ShortArray): Int = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

    override fun close() {
        closed = true
        try {
            if (Build.VERSION.SDK_INT >= 29) callback?.let { recorder.unregisterAudioRecordingCallback(it) }
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } finally { recorder.release() }
    }
}
