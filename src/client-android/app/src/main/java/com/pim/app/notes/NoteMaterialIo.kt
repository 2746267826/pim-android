package com.pim.app.notes

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun materialDigest(input: InputStream, checkActive: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun materialDigest(text: String): String = text.byteInputStream().use { materialDigest(it) }
internal fun validMaterialDigest(value: String) = value.matches(Regex("[a-f0-9]{64}"))

private val materialFileLocks = ConcurrentHashMap<String, Any>()

private fun <T> withMaterialFileLock(file: File, block: () -> T): T {
    val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }.lowercase(Locale.ROOT)
    return synchronized(materialFileLocks.computeIfAbsent(key) { Any() }) { block() }
}

private fun atomicWriteMaterialFile(file: File, bytes: ByteArray): Boolean = try {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(bytes)
        stream.fd.sync()
        atomic.finishWrite(stream)
    } catch (error: Exception) {
        runCatching { atomic.failWrite(stream) }
        throw error
    }
    true
} catch (_: Exception) {
    false
}

private fun replaceMaterialFileFromPending(file: File, bytes: ByteArray) {
    val parent = file.parentFile!!
    val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.pending")
    try {
        java.io.FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}

private fun writeMaterialBytesVerified(file: File, bytes: ByteArray) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val atomicReadBack = atomicWriteMaterialFile(file, bytes) &&
        runCatching { AtomicFile(file).readFully().contentEquals(bytes) }.getOrDefault(false)
    if (atomicReadBack) return

    replaceMaterialFileFromPending(file, bytes)
    check(
        runCatching { AtomicFile(file).readFully().contentEquals(bytes) }.getOrDefault(false),
    ) {
        "Material checkpoint was not confirmed on disk"
    }
    // The replacement is confirmed; only now retire AtomicFile's previous generation.
    File(file.path + ".bak").delete()
}

internal fun writeMaterialFile(file: File, write: (OutputStream) -> Unit) {
    withMaterialFileLock(file) {
        val bytes = ByteArrayOutputStream().use { output ->
            write(output)
            output.toByteArray()
        }
        writeMaterialBytesVerified(file, bytes)
    }
}

internal fun readMaterialFile(file: File): String = withMaterialFileLock(file) {
    AtomicFile(file).readFully().toString(Charsets.UTF_8)
}
internal fun materialFileExists(file: File) = withMaterialFileLock(file) {
    file.isFile || File(file.path + ".bak").isFile
}

/**
 * Writes JSON through AtomicFile and verifies the exact bytes. Some Windows
 * filesystems can leave the previous backup readable after an atomic replace;
 * the conservative fallback keeps the same "confirmed on disk" contract.
 */
internal fun writeVerifiedMaterialJson(file: File, json: String) {
    writeMaterialFile(file) { it.write(json.toByteArray(Charsets.UTF_8)) }
}

/** Each unique byte sequence is copied once; later versions pin the same private blob. */
internal class NoteMaterialBlobs(private val root: File) {
    fun file(hash: String): File {
        require(validMaterialDigest(hash))
        return File(root, hash)
    }

    fun verify(material: NoteMaterialFile, checkActive: () -> Unit = {}) {
        val target = file(material.sha256)
        require(target.isFile && target.length() == material.sizeBytes) { "Missing pinned material" }
        require(target.inputStream().use { materialDigest(it, checkActive) } == material.sha256) { "Pinned material changed" }
    }

    fun pin(size: Long, expectedHash: String?, checkActive: () -> Unit, open: () -> InputStream): String {
        require(size >= 0)
        val hash = open().use { materialDigest(it, checkActive) }
        require(expectedHash == null || expectedHash == hash) { "Source integrity mismatch" }
        val target = file(hash)
        if (target.exists()) {
            verify(NoteMaterialFile("", NoteMaterialKind.Document, hash, size), checkActive)
            return hash
        }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create private material directory")
        if (root.usableSpace < size + 1024 * 1024L) throw IOException("Insufficient space to pin material")
        val temporary = File(root, "${newNoteId()}.pending")
        try {
            temporary.outputStream().use { output ->
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= size) { "Source changed while pinning" }
                        output.write(buffer, 0, count)
                    }
                    require(copied == size) { "Source length changed" }
                }
                output.fd.sync()
            }
            require(temporary.inputStream().use { materialDigest(it, checkActive) } == hash) { "Source changed while pinning" }
            if (target.exists() || !temporary.renameTo(target)) throw IOException("Cannot finalize pinned material")
            return hash
        } finally { temporary.delete() }
    }
}
