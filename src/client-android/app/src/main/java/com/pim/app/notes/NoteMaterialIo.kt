package com.pim.app.notes

import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

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

internal fun writeMaterialFile(file: File, write: (OutputStream) -> Unit) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try { write(stream); stream.fd.sync(); atomic.finishWrite(stream) }
    catch (error: Exception) { atomic.failWrite(stream); throw error }
}

internal fun readMaterialFile(file: File): String = AtomicFile(file).readFully().toString(Charsets.UTF_8)
internal fun materialFileExists(file: File) = file.isFile || File(file.path + ".bak").isFile

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
