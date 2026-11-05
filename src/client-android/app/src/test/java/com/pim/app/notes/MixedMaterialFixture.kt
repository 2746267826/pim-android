package com.pim.app.notes

import android.util.Base64
import com.pim.app.recording.RecordingChunk
import com.pim.app.recording.RecordingFileStore
import com.pim.app.recording.RecordingPhase
import com.pim.app.recording.RecordingSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MixedMaterialFixture {
    const val NOTE_ID = "11111111-1111-4111-8111-111111111111"
    const val VERSION_ID = "22222222-2222-4222-8222-222222222222"
    const val MARKDOWN_ID = "33333333-3333-4333-8333-333333333333"
    const val PHOTO_BLOCK_ID = "44444444-4444-4444-8444-444444444444"
    const val PHOTO_ASSET_ID = "55555555-5555-4555-8555-555555555555"
    const val INK_BLOCK_ID = "66666666-6666-4666-8666-666666666666"
    const val RECORDING_ID = "77777777-7777-4777-8777-777777777777"
    const val TAIL_MARKDOWN_ID = "88888888-8888-4888-8888-888888888888"
    const val NOTE_CREATED_AT = 1_735_689_600_000L
    const val RECORDING_CREATED_AT = 1_735_689_601_000L
    const val MATERIAL_CREATED_AT = 1_735_689_602_000L

    private val photoBytes = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        Base64.DEFAULT,
    )
    private val firstSamples = shortArrayOf(1000, -1000, 2000, -2000)
    private val secondSamples = shortArrayOf(3000, -3000, 4000, -4000, 5000, -5000)

    data class Prepared(
        val root: File,
        val notes: NoteFileStore,
        val recordings: RecordingFileStore,
        val materials: NoteMaterialStore,
        val note: NoteDocument,
        val session: RecordingSession,
        val input: NoteMaterialInput,
        val manifest: NoteMaterialManifest,
        val snapshot: NoteUploadSnapshot,
    ) {
        fun pinnedBytes(file: NoteMaterialFile): ByteArray =
            File(root, "note-material-versions/blobs/${file.sha256}").readBytes()

        fun canonicalFileSummary(): List<String> = snapshot.files.map { file ->
            "${file.path}|${file.sha256}|${file.sizeBytes}"
        }

        fun exportAt(directory: File): Exported {
            require(directory.path.replace('\\', '/').endsWith("app/build/interop/mixed-material-v1"))
            if (directory.exists()) check(directory.deleteRecursively())
            check(directory.mkdirs())
            directory.resolve("input.json").writeText(snapshot.inputJson)
            directory.resolve("manifest.json").writeText(snapshot.manifestJson)
            snapshot.files.forEach { file ->
                val target = directory.resolve(file.path)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                target.writeBytes(pinnedBytes(file))
            }
            return Exported(directory.canonicalFile, materialDigest(snapshot.inputJson), materialDigest(snapshot.manifestJson), canonicalFileSummary())
        }
    }

    data class Exported(
        val directory: File,
        val inputSha256: String,
        val manifestSha256: String,
        val fileSummary: List<String>,
    )

    fun install(root: File): Prepared {
        val notes = NoteFileStore(File(root, "notes"))
        val recordings = RecordingFileStore(File(root, "recordings"))
        val materials = NoteMaterialStore.at(root)
        val note = note()
        val photo = File(root, "notes/$NOTE_ID/assets/$PHOTO_ASSET_ID.png")
        check(photo.parentFile!!.mkdirs())
        photo.writeBytes(photoBytes)
        notes.save(note)

        val session = recording()
        val recordingFolder = File(root, "recordings/$RECORDING_ID")
        check(recordingFolder.mkdirs())
        recordingFolder.resolve(session.chunks[0].fileName).writeBytes(wav(firstSamples))
        recordingFolder.resolve(session.chunks[1].fileName).writeBytes(wav(secondSamples))
        recordings.save(session)

        val input = NoteMaterialInput(VERSION_ID, MATERIAL_CREATED_AT, note.source, listOf(session))
        val inputFile = File(root, "note-material-versions/$VERSION_ID/input.json")
        check(inputFile.parentFile!!.mkdirs())
        inputFile.writeText(NoteJson.encodeToString(input))
        val manifest = materials.prepare(VERSION_ID)
        return Prepared(root, notes, recordings, materials, note, session, input, manifest, materials.uploadSnapshot(VERSION_ID))
    }

    private fun note(): NoteDocument {
        val photo = buildJsonObject {
            put("id", PHOTO_BLOCK_ID)
            put("kind", "photo")
            put("assetId", PHOTO_ASSET_ID)
            put("mimeType", "image/png")
            put("sha256", materialDigest(photoBytes.inputStream()))
        }
        val ink = withInk(
            buildJsonObject { put("id", INK_BLOCK_ID); put("kind", "ink") },
            InkData(strokes = listOf(InkStroke(listOf(InkPoint(12f, 18f), InkPoint(96f, 84f), InkPoint(180f, 30f))))),
        )
        return NoteDocument(buildJsonObject {
            put("schemaVersion", 1)
            put("id", NOTE_ID)
            put("title", "Canonical mixed materials")
            put("revision", 7)
            put("createdAtMillis", NOTE_CREATED_AT)
            put("blocks", JsonArray(listOf(
                markdownBlock("# Canonical note\n\n**keep this body**\n"),
                photo,
                ink,
                buildJsonObject { put("id", TAIL_MARKDOWN_ID); put("kind", "markdown"); put("text", "tail\n") },
            ).mapIndexed { index, block ->
                if (index == 0) JsonObject(block + ("id" to JsonPrimitive(MARKDOWN_ID))) else block
            }))
        })
    }

    private fun recording() = RecordingSession(
        id = RECORDING_ID,
        createdAtMillis = RECORDING_CREATED_AT,
        phase = RecordingPhase.Completed,
        currentSegment = 1,
        chunks = listOf(
            RecordingChunk(segment = 1, index = 1, samples = firstSamples.size.toLong()),
            RecordingChunk(segment = 1, index = 2, samples = secondSamples.size.toLong()),
        ),
        noteId = NOTE_ID,
    )

    private fun wav(samples: ShortArray): ByteArray = ByteBuffer.allocate(44 + samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + samples.size * 2)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(samples.size * 2)
            samples.forEach(::putShort)
        }.array()
}
