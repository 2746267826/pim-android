package com.pim.app.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class NoteEntry(val id: String, val title: String, val damaged: Boolean = false)

class NoteFileStore(private val root: File) {
    fun saveInsertion(anchor: NoteInsertion): String {
        anchor.validate()
        val token = newNoteId()
        atomicWrite(insertionFile(token)) { it.write(NoteJson.encodeToString(anchor).toByteArray(Charsets.UTF_8)) }
        return token
    }

    fun readInsertion(token: String): NoteInsertion = NoteJson.decodeFromString<NoteInsertion>(
        AtomicFile(insertionFile(token)).readFully().toString(Charsets.UTF_8)
    ).also { it.validate() }

    fun removeInsertion(token: String) { AtomicFile(insertionFile(token)).delete() }

    private fun insertionFile(token: String): File {
        require(validNoteId(token))
        return File(root, ".insertions/$token.json")
    }

    fun entries(): List<NoteEntry> = root.listFiles().orEmpty().filter { it.isDirectory && validNoteId(it.name) }
        .sortedByDescending { it.lastModified() }.map {
            runCatching { read(it.name).let { note -> NoteEntry(note.id, note.title) } }
                .getOrElse { _ -> NoteEntry(it.name, "无法读取的记录（原材料保留）", true) }
        }

    fun read(id: String) = NoteDocument.decode(AtomicFile(File(directory(id), "note.json")).readFully().toString(Charsets.UTF_8))
        .also { require(it.id == id) }

    @Synchronized fun save(note: NoteDocument) {
        note.validate()
        val folder = directory(note.id).apply { check(isDirectory || mkdirs()) }
        val manifest = AtomicFile(File(folder, "note.json"))
        if (manifest.baseFile.exists() || File(folder, "note.json.bak").exists()) {
            val previous = read(note.id)
            require(previous.revision < note.revision || previous.source == note.source) { "Refusing stale document write" }
        }
        prepareInk(note)
        // The editable source is authoritative; previews are regenerable, version-specific projections.
        atomicWrite(File(folder, "note.json")) { it.write(note.source.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun prepareInk(note: NoteDocument, regenerate: Boolean = false) {
        val folder = directory(note.id)
        note.blocks.filter { it.string("kind") == "ink" }.forEach { block ->
            val target = File(folder, inkPath(block))
            if (regenerate || !target.exists()) atomicWrite(target) { stream ->
                val bitmap = InkRendering.bitmap(inkOf(block))
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) } finally { bitmap.recycle() }
            }
        }
    }

    fun importPhoto(id: String, input: InputStream): JsonObject {
        val asset = newNoteId()
        val file = File(directory(id), "assets/$asset.pending")
        atomicWrite(file) { output ->
            val bytes = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= MAX_PHOTO_BYTES) { "Photo exceeds local import limit" }
                output.write(bytes, 0, count)
            }
            require(total > 0)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
            "Unsupported or invalid photo; original staging material retained"
        }
        val decoded = photoPreview(file)
        require(decoded != null) { "Image header exists but pixels cannot be decoded" }
        decoded.recycle()
        val target = File(directory(id), "assets/$asset.${photoExtension(bounds.outMimeType)}")
        check(!target.exists() && file.renameTo(target)) { "Cannot finalize private photo" }
        return buildJsonObject {
            put("id", newNoteId()); put("kind", "photo"); put("assetId", asset)
            put("mimeType", bounds.outMimeType); put("sha256", target.inputStream().use { hashStream(it) })
        }
    }

    fun photoFile(id: String, asset: String): File {
        require(validNoteId(asset))
        return listOf("jpg", "png", "webp").map { File(directory(id), "assets/$asset.$it") }.firstOrNull { it.isFile }
            ?: File(directory(id), "assets/$asset.jpg")
    }

    @Synchronized fun exportDocument(note: NoteDocument, output: OutputStream) {
        note.validate()
        prepareInk(note, regenerate = true)
        val folder = directory(note.id)
        val materials = note.blocks.mapNotNull { block -> when (block.string("kind")) {
            "photo" -> photoPath(block)
            "ink" -> inkPath(block)
            else -> null
        } }.distinct()
        require(materials.all { File(folder, it).isFile }) { "Missing document material" }
        note.blocks.filter { it.string("kind") == "photo" }.forEach { block ->
            val actual = File(folder, photoPath(block)).inputStream().use { hashStream(it) }
            require(block["sha256"]?.jsonPrimitive?.content == actual) { "Photo integrity check failed" }
        }
        // Unknown extensions cannot be safely packaged unless their material semantics are known.
        require(note.blocks.all { it.string("kind") in setOf("markdown", "photo", "ink") }) { "Unknown extension prevents complete export" }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, write: () -> Unit) {
                zip.putNextEntry(ZipEntry(name)); write(); zip.closeEntry()
            }
            entry("note.json") { zip.write(note.source.toString().toByteArray(Charsets.UTF_8)) }
            entry("document.md") { zip.write(markdown(note).toByteArray(Charsets.UTF_8)) }
            materials.forEach { path -> entry(path) { File(folder, path).inputStream().use { it.copyTo(zip) } } }
        }
    }

    @Synchronized fun importDocument(input: InputStream): NoteDocument {
        val id = newNoteId()
        val importRoot = File(root, ".import-failures")
        check(importRoot.isDirectory || importRoot.mkdirs()) { "Cannot create import staging directory" }
        check(importRoot.usableSpace >= MIN_IMPORT_SPACE_BYTES) { "Insufficient space to import the document package" }
        val staging = File(importRoot, id)
        check(staging.mkdirs()) { "Cannot create import staging folder" }
        try {
            val entries = mutableSetOf<String>()
            var manifest: ByteArray? = null
            var markdownProjection: ByteArray? = null
            val stagedAssets = mutableSetOf<String>()
            var entryCount = 0
            var expandedBytes = 0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Document package has too many entries" }
                    require(validArchivePath(entry.name)) { "Unsafe document package path" }
                    require(entries.add(entry.name)) { "Duplicate document package path" }
                    require(!entry.isDirectory) { "Document package directories are not artifacts" }
                    val bytes = when {
                        entry.name == "note.json" -> readEntry(zip, MAX_MANIFEST_BYTES)
                        entry.name == "document.md" -> readEntry(zip, MAX_MARKDOWN_BYTES)
                        PHOTO_ENTRY.matches(entry.name) -> readEntry(zip, MAX_PHOTO_BYTES.toInt())
                        INK_ENTRY.matches(entry.name) -> readEntry(zip, MAX_INK_PREVIEW_BYTES)
                        else -> throw IllegalArgumentException("Unknown document package entry")
                    }
                    expandedBytes += bytes.size
                    require(expandedBytes <= MAX_EXPANDED_BYTES) { "Document package expands beyond the local budget" }
                    when {
                        entry.name == "note.json" -> manifest = bytes
                        entry.name == "document.md" -> markdownProjection = bytes
                        entry.name.startsWith("assets/") -> {
                            val target = File(staging, entry.name)
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                            target.writeBytes(bytes)
                            stagedAssets += entry.name
                        }
                    }
                }
            }
            val exported = NoteDocument.decode(requireNotNull(manifest).toString(Charsets.UTF_8))
            requireNotNull(markdownProjection) { "Missing document.md projection" }
            require(exported.blocks.all { it.string("kind") in setOf("markdown", "photo", "ink") }) {
                "Package contains extensions with unknown material semantics"
            }
            exported.blocks.filter { it.string("kind") == "photo" }.forEach { block ->
                require(block.string("mimeType") in setOf("image/jpeg", "image/png", "image/webp"))
                val photo = File(staging, photoPath(block))
                require(photo.isFile && photo.length() <= MAX_PHOTO_BYTES)
                require(validMaterialDigest(block.string("sha256")))
                require(photo.inputStream().use(::hashStream) == block.string("sha256")) { "Photo integrity check failed" }
            }
            exported.blocks.filter { it.string("kind") == "ink" }.forEach { block ->
                val preview = File(staging, inkPath(block))
                require(preview.isFile) { "Missing editable ink preview" }
                require(preview.inputStream().use { it.readNBytesCompat(8) }.contentEquals(PNG_SIGNATURE)) {
                    "Editable ink preview is not a PNG"
                }
            }
            val imported = exported.copy(source = JsonObject(exported.source + mapOf(
                "id" to JsonPrimitive(id),
                "revision" to JsonPrimitive(0),
            )))
            imported.validate()
            require(markdownProjection!!.toString(Charsets.UTF_8) == markdown(imported)) {
                "document.md does not match the editable source"
            }
            val expectedAssets = imported.blocks.mapNotNull { block ->
                when (block.string("kind")) {
                    "photo" -> photoPath(block)
                    "ink" -> inkPath(block)
                    else -> null
                }
            }.toSet()
            require(stagedAssets == expectedAssets && expectedAssets.size == stagedAssets.size) {
                "Document package does not contain exactly the referenced materials"
            }
            atomicWrite(File(staging, "note.json")) {
                it.write(imported.source.toString().toByteArray(Charsets.UTF_8))
            }
            val target = directory(id)
            check(!target.exists() && staging.renameTo(target)) { "Cannot finalize imported document" }
            val visible = read(id)
            require(visible.id == imported.id && visible.blocks == imported.blocks)
            return visible
        } catch (error: Exception) {
            // Keep the exact received entries for manual recovery instead of publishing a partial note.
            if (staging.exists() && File(root, id).exists().not()) staging.renameTo(File(importRoot, "$id-failed"))
            throw error
        }
    }

    fun markdown(note: NoteDocument): String = note.blocks.joinToString("\n\n") { block -> when (block.string("kind")) {
        "markdown" -> block.string("text")
        "photo" -> "![照片](${photoPath(block)})"
        "ink" -> "![手写卡片](${inkPath(block)})\n<!-- 可编辑笔迹、身份和尺寸保存在同包 note.json 中 -->"
        else -> "<!-- 未知扩展保留在 note.json 中，不可视为已完整导出 -->"
    } }

    private fun directory(id: String): File { require(validNoteId(id)); return File(root, id) }

    private fun atomicWrite(file: File, write: (OutputStream) -> Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { write(stream); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    companion object {
        const val MAX_PHOTO_BYTES = 32 * 1024 * 1024L
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        private const val MAX_MARKDOWN_BYTES = 16 * 1024 * 1024
        private const val MAX_INK_PREVIEW_BYTES = 16 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 192L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 2048
        private const val MIN_IMPORT_SPACE_BYTES = 64L * 1024 * 1024
        private val PHOTO_ENTRY = Regex("""assets/[0-9a-f-]{36}\.(?:jpg|png|webp)""")
        private val INK_ENTRY = Regex("""assets/[0-9a-f-]{36}-[0-9a-f]{64}\.png""")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private fun photoExtension(mime: String?): String = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        internal fun photoPath(block: JsonObject): String = "assets/${block.string("assetId")}.${photoExtension(block["mimeType"]?.jsonPrimitive?.content)}"
        internal fun inkPath(block: JsonObject): String = "assets/${block.string("id")}-${hash(block.getValue("ink").toString())}.png"
        private fun validArchivePath(path: String): Boolean =
            path == path.trim() && !path.startsWith('/') && !path.contains('\\') &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Document package entry exceeds the local budget" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        private fun InputStream.readNBytesCompat(count: Int): ByteArray {
            val output = ByteArrayOutputStream(count)
            val buffer = ByteArray(count)
            var total = 0
            while (total < count) {
                val read = read(buffer, 0, count - total)
                if (read < 0) break
                output.write(buffer, 0, read); total += read
            }
            return output.toByteArray()
        }
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun hashStream(input: InputStream): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }

        fun photoPreview(file: File, maxSize: Int = 1200): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSize) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
            val matrix = Matrix()
            runCatching {
                val exif = ExifInterface(file)
                matrix.postRotate(exif.rotationDegrees.toFloat())
                if (exif.isFlipped) matrix.postScale(-1f, 1f)
            }
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        }
    }
}
