package com.pim.app.notes

import android.content.Context
import com.pim.app.recording.RecordingSession
import com.pim.core.auth.TokenManager
import com.pim.core.contract.NativeNoteSummaryDto
import com.pim.core.contract.NoteMaterialVersionDto
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.BoundUploadSession
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import com.pim.core.settings.ServerSessionCommitResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

data class CloudNoteEntry(val summary: NativeNoteSummaryDto, val problem: String? = null)
data class CloudNotesState(
    val loaded: Boolean = false,
    val entries: List<CloudNoteEntry> = emptyList(),
    val loading: Boolean = false,
    val unsupported: Boolean = false,
    val problem: String? = null,
)

data class CloudNoteTarget(val binding: BoundUploadSession, val generation: Long)

@Singleton
class CloudNoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clients: ApiClientProvider,
    private val sessions: TokenManager,
    private val settings: ServerSettingsStore,
) {
    private val store = NoteFileStore(File(context.noBackupFilesDir, "notes"))
    private val archiveRoot = File(context.noBackupFilesDir, "cloud-recording-archives")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CloudNotesState())
    val state = mutableState.asStateFlow()

    fun target(): CloudNoteTarget? = runCatching {
        val origin = PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin
        var result: CloudNoteTarget? = null
        settings.commitSessionIfCurrentServer(origin) {
            val before = sessions.sessionIdentityGeneration()
            val session = sessions.snapshot()
            val after = sessions.sessionIdentityGeneration()
            val ownerId = session.ownerId
            if (before != after || session.tokens == null || session.serverIdentity != origin || ownerId == null) false
            else {
                result = CloudNoteTarget(BoundUploadSession(origin, ownerId), after)
                true
            }
        }
        result
    }.getOrNull()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = target()
            if (target == null) {
                mutableState.value = CloudNotesState(loaded = true, problem = "登录后可查看云端笔记")
                return@withLock
            }
            mutableState.value = state.value.copy(loading = true, problem = null, unsupported = false)
            try {
                val api = clients.noteUploadApiService(target.binding)
                val collected = mutableListOf<CloudNoteEntry>()
                var offset = 0
                var total: Int
                do {
                    checkCurrent(target)
                    val page = api.listNativeNotes(offset, PAGE_SIZE)
                    val data = page.data
                    if (page.code != 0 || data == null) throw IOException(page.message)
                    total = data.total
                    collected += data.items.map(::CloudNoteEntry)
                    offset += data.items.size
                    require(data.items.size <= PAGE_SIZE && offset <= total && offset < MAX_NOTES)
                } while (offset < total && collected.size < MAX_NOTES)
                checkCurrent(target)
                mutableState.value = CloudNotesState(loaded = true, entries = collected)
            } catch (error: CancellationException) { throw error }
            catch (error: retrofit2.HttpException) {
                if (error.code() == 404) mutableState.value = CloudNotesState(
                    loaded = true, unsupported = true, problem = "当前服务器暂不支持同步，仍可本机记录"
                ) else mutableState.value = state.value.copy(loaded = true, loading = false, problem = "云端笔记暂时无法读取")
            } catch (_: Exception) {
                if (target() == target) mutableState.value = state.value.copy(loaded = true, loading = false, problem = "云端笔记暂时无法读取")
            }
        }
    }

    suspend fun restore(noteId: String): NoteDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = target() ?: throw IOException("请登录后打开云端笔记")
            val entry = state.value.entries.firstOrNull { it.summary.noteId == noteId }
                ?: throw IOException("云端笔记已不在当前列表中，请刷新")
            val summary = entry.summary
            checkCurrent(target)
            val response = clients.noteUploadApiService(target.binding).getCurrentNativeNoteVersion(noteId)
            val current = requireData(response.code, response.data, response.message)
            require(current.upload.noteId == noteId && current.upload.versionId == summary.currentVersionId && current.upload.isCurrent) {
                "云端版本已变化，请刷新后重试"
            }
            val restored = restoreVersion(current, target)
            checkCurrent(target)
            if (restored.third.isNotEmpty()) {
                require(!File(archiveRoot, noteId).exists()) { "该笔记的录音归档已存在；不会覆盖本机原件" }
            }
            if (restored.third.isNotEmpty()) installRecordingArchive(noteId, restored.third)
            store.installCloudDocument(restored.first, restored.second)
            restored.first
        }
    }

    fun hasReadOnlyRecordingArchive(noteId: String): Boolean = File(archiveRoot, noteId).isDirectory

    private suspend fun restoreVersion(version: NoteMaterialVersionDto, target: CloudNoteTarget): Triple<NoteDocument, Map<String, ByteArray>, Map<String, ByteArray>> {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = false }
        val input = json.parseToJsonElement(version.inputJson).jsonObject
        require(input.keys.containsAll(setOf("note", "recordings"))) { "云端笔记缺少正文或材料清单" }
        val note = NoteDocument(input.getValue("note").jsonObject).also { it.validate() }
        require(note.id == version.upload.noteId && note.blocks.all { it.string("kind") in setOf("markdown", "photo", "ink") }) {
            "云端正文包含不支持的内容，材料已隔离，未安装空白笔记"
        }
        val manifest = json.decodeFromString<NoteMaterialManifest>(version.manifestJson)
        require(manifest.noteId == note.id && manifest.versionId == version.upload.versionId &&
            manifest.revision == note.revision && manifest.inputSha256 == sha256(version.inputJson)) { "云端材料清单与正文不匹配" }
        val descriptors = manifest.files
        require(descriptors.isNotEmpty() && descriptors.size <= MAX_FILES &&
            descriptors.map { it.path }.distinct().size == descriptors.size)
        val uploadFiles = version.upload.files.sortedBy { it.index }
        require(uploadFiles.size == descriptors.size && uploadFiles.indices.all { index ->
            val remote = uploadFiles[index]; val file = descriptors[index]
            remote.index == index && remote.path == file.path && remote.kind == file.kind.name &&
                remote.sha256 == file.sha256 && remote.sizeBytes == file.sizeBytes
        }) { "云端文件索引与清单不一致" }
        val totalBytes = descriptors.sumOf { it.sizeBytes }
        require(totalBytes in 1..MAX_VERSION_BYTES && descriptors.all { it.sizeBytes in 0..MAX_FILE_BYTES && validMaterialDigest(it.sha256) }) {
            "云端材料超过本机安全预算或哈希无效"
        }
        val expectedPaths = note.blocks.mapNotNull { block -> when (block.string("kind")) {
            "photo" -> NoteFileStore.photoPath(block)
            "ink" -> NoteFileStore.inkPath(block)
            else -> null
        } }.toSet()
        require(descriptors.any { it.path == "note.json" } && descriptors.any { it.path == "document.md" })
        require(descriptors.all { file ->
            safeMaterialPath(file.path) && when (file.kind) {
                NoteMaterialKind.Document -> file.path == "note.json"
                NoteMaterialKind.Markdown -> file.path == "document.md"
                NoteMaterialKind.Photo -> file.path in expectedPaths && file.path.startsWith("assets/")
                NoteMaterialKind.InkPreview -> file.path in expectedPaths && file.path.startsWith("assets/")
                NoteMaterialKind.RecordingManifest -> file.path.matches(Regex("recordings/[0-9a-f-]{36}/manifest\\.json"))
                NoteMaterialKind.AudioChunk -> file.path.matches(Regex("recordings/[0-9a-f-]{36}/s[0-9]{4,}-c[0-9]{6,}\\.wav"))
            }
        }) { "云端材料类型与路径不一致" }
        require(descriptors.filter { it.kind in setOf(NoteMaterialKind.Photo, NoteMaterialKind.InkPreview) }
            .map { it.path }.toSet() == expectedPaths)
        val localFiles = linkedMapOf<String, ByteArray>()
        val recordings = linkedMapOf<String, ByteArray>()
        val api = clients.noteUploadApiService(target.binding)
        for ((index, descriptor) in descriptors.withIndex()) {
            require(safeMaterialPath(descriptor.path)) { "云端材料路径非法" }
            val body = api.downloadNoteMaterialFile(version.upload.versionId, uploadFiles[index].index)
            checkCurrent(target)
            if (!body.isSuccessful) throw IOException("云端材料下载失败 (${body.code()})")
            val bytes = body.body()?.byteStream()?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= descriptor.sizeBytes && size <= MAX_FILE_BYTES) { "云端文件大小超出清单" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IOException("云端文件内容为空")
            require(bytes.size.toLong() == descriptor.sizeBytes && sha256(bytes) == descriptor.sha256) { "云端材料哈希或大小校验失败" }
            when {
                descriptor.path == "note.json" -> require(NoteDocument.decode(bytes.toString(Charsets.UTF_8)).source == note.source)
                descriptor.path.startsWith("recordings/") -> recordings[descriptor.path] = bytes
                descriptor.path in expectedPaths -> localFiles[descriptor.path] = bytes
                descriptor.path == "document.md" -> Unit
                else -> throw IllegalArgumentException("云端材料路径与笔记正文不匹配")
            }
        }
        require(localFiles.keys == expectedPaths) { "云端照片或笔迹材料不完整" }
        require(sha256(version.inputJson) == manifest.inputSha256)
        validateRecordings(json, input.getValue("recordings") as JsonArray, note.id, descriptors, recordings)
        return Triple(note, localFiles, recordings)
    }

    private fun validateRecordings(json: kotlinx.serialization.json.Json, elements: JsonArray, noteId: String,
        files: List<NoteMaterialFile>, downloaded: Map<String, ByteArray>) {
        val sessions = json.decodeFromJsonElement<List<RecordingSession>>(elements)
        val expected = mutableSetOf<String>()
        sessions.forEach { session ->
            require(session.noteId == noteId && session.schemaVersion == 1 && !session.active &&
                session.problem != com.pim.app.recording.RecordingProblem.Damaged && session.chunks.isNotEmpty())
            val manifestPath = "recordings/${session.id}/manifest.json"
            expected += manifestPath
            val manifestBytes = downloaded[manifestPath]
            val manifestFile = files.singleOrNull { it.path == manifestPath && it.kind == NoteMaterialKind.RecordingManifest }
            require(manifestBytes != null && manifestFile != null && sha256(manifestBytes) == manifestFile.sha256 &&
                json.decodeFromString<RecordingSession>(manifestBytes.toString(Charsets.UTF_8)) == session) {
                "云端录音清单与录音不匹配"
            }
            require(!session.active && session.chunks.isNotEmpty() && session.chunks.all { chunk ->
                val path = "recordings/${session.id}/${chunk.fileName}"
                expected += path
                val descriptor = files.singleOrNull { it.path == path && it.kind == NoteMaterialKind.AudioChunk }
                val bytes = downloaded[path]
                descriptor != null && bytes != null && bytes.size.toLong() == descriptor.sizeBytes && sha256(bytes) == descriptor.sha256 &&
                    runCatching {
                        val temp = File.createTempFile("cloud-audio", ".wav", context.cacheDir)
                        try { temp.writeBytes(bytes); com.pim.app.recording.PcmWavFile.validate(temp, chunk.samples) }
                        finally { temp.delete() }
                        true
                    }.getOrDefault(false)
            }) { "录音材料不完整或损坏，原件未发布" }
        }
        require(downloaded.keys == expected && files.filter { it.path.startsWith("recordings/") }.map { it.path }.toSet() == expected) {
            "云端录音清单不完整"
        }
    }

    private fun installRecordingArchive(noteId: String, files: Map<String, ByteArray>) {
        val target = File(archiveRoot, noteId)
        require(!target.exists()) { "该笔记的录音归档已存在；不会覆盖本机原件" }
        val staging = File(archiveRoot, ".$noteId-${System.nanoTime()}")
        check(staging.mkdirs())
        try {
            files.forEach { (path, bytes) ->
                require(safeMaterialPath(path) && path.startsWith("recordings/"))
                val output = File(staging, path)
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                output.writeBytes(bytes)
            }
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            check(staging.renameTo(target))
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }

    private fun checkCurrent(expected: CloudNoteTarget) {
        check(target() == expected) { "账号或服务器已切换，已取消云端操作" }
    }

    private fun <T> requireData(code: Int, data: T?, message: String): T {
        if (code != 0 || data == null) throw IOException(message)
        return data
    }

    private fun safeMaterialPath(path: String) = path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun sha256(value: String) = sha256(value.toByteArray(Charsets.UTF_8))
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_NOTES = 5000
        private const val MAX_FILES = 2048
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private const val MAX_VERSION_BYTES = NoteFileStore.MAX_CLOUD_RESTORE_BYTES
    }
}
