package com.pim.app.recording

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class PcmWavFile(private val file: File) : Closeable {
    private val output: RandomAccessFile
    var samples: Long = 0
        private set

    init {
        check(file.createNewFile()) { "Audio file already exists" }
        output = RandomAccessFile(file, "rw")
        try { checkpoint() } catch (error: Exception) { output.close(); throw error }
    }

    fun append(input: ShortArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset + count <= input.size)
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in offset until offset + count) bytes.putShort(input[i])
        output.seek(HEADER_BYTES + samples * 2)
        output.write(bytes.array())
        samples += count
    }

    fun checkpoint() {
        output.seek(0)
        output.write(header(samples * 2))
        output.fd.sync()
    }

    override fun close() {
        try { checkpoint() } finally { output.close() }
    }

    companion object {
        private const val HEADER_BYTES = 44L

        fun validate(file: File, samples: Long) {
            require(samples in 0..((Int.MAX_VALUE - 36L) / 2))
            RandomAccessFile(file, "r").use { input ->
                require(input.length() == HEADER_BYTES + samples * 2) { "WAV length disagrees with recording manifest" }
                val actual = ByteArray(HEADER_BYTES.toInt()).also { input.readFully(it) }
                require(actual.contentEquals(header(samples * 2))) { "WAV header disagrees with recording manifest" }
            }
        }

        // The payload is PCM, so process-death recovery needs no codec finalization.
        fun recover(file: File): Long {
            RandomAccessFile(file, "rw").use { input ->
                require(input.length() >= HEADER_BYTES) { "Incomplete WAV header" }
                val actual = ByteArray(HEADER_BYTES.toInt()).also { input.readFully(it) }
                val expected = header(0)
                val fixedFields = (0..3) + (8..39)
                require(fixedFields.all { actual[it] == expected[it] }) { "Unsupported WAV format" }
                val bytes = (input.length() - HEADER_BYTES) / 2 * 2
                input.seek(0)
                input.write(header(bytes))
                input.fd.sync()
                return bytes / 2
            }
        }

        private fun header(bytes: Long): ByteArray {
            require(bytes in 0..(Int.MAX_VALUE - 36).toLong())
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((bytes + 36).toInt())
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(1); putInt(RecordingPolicy.SAMPLE_RATE)
                putInt(RecordingPolicy.SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(bytes.toInt())
            }.array()
        }
    }
}
