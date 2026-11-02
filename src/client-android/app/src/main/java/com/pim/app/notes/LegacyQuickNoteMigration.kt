package com.pim.app.notes

import android.content.Context
import android.util.AtomicFile
import com.pim.app.nativework.NativeWorkbenchSession
import com.pim.app.nativework.NativeWorkbenchTransport
import com.pim.core.contract.QuickNoteAttachmentDto
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.network.BoundUploadSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_LEGACY_CONTENT_BYTES = 512 * 1024
private const val MAX_LEGACY_METADATA_BYTES = 128 * 1024
private const val MAX_LEGACY_ATTACHMENTS = 50
private const val MAX_MIGRATION_LINKS = 5_000

@Serializable
data class LegacyQuickNoteMigrationLink(
    val schemaVersion: Int = 1,
    val serverIdentity: String,
    val ownerId: String,
    val legacyNoteId: String,
    val noteId: String,
    val createdAtMillis: Long,
)

internal val LegacyQuickNoteMigrationLink.migrationKey: String
    get() = "$serverIdentity\u0000$ownerId\u0000$legacyNoteId"

data class LegacyQuickNoteMigrationIdentity(
    val binding: BoundUploadSession,
    val legacyNoteId: String,
) {
    init { require(legacyNoteId.isNotBlank() && legacyNoteId.length <= 128) }

    val stableKey: String = sha256("${binding.serverIdentity}\u0000${binding.ownerId}\u0000$legacyNoteId")
    val noteId: String = UUIDFromDigest(stableKey)
}

internal val LegacyQuickNoteMigrationIdentity.migrationKey: String
    get() = "${binding.serverIdentity}\u0000${binding.ownerId}\u0000$legacyNoteId"

data class LegacyQuickNoteMigrationState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val confirming: Boolean = false,
    val error: String? = null,
    val feedback: String? = null,
    val session: NativeWorkbenchSession? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
    val items: List<LegacyQuickNoteSummary> = emptyList(),
    val selected: QuickNoteDetailDto? = null,
    val migratedNoteId: String? = null,
    val links: List<LegacyQuickNoteMigrationLink> = emptyList(),
)

data class LegacyQuickNoteSummary(
    val id: String,
    val createdAt: String,
    val updatedAt: String,
    val status: String,
    val source: String,
    val contentPreview: String,
    val attachmentCount: Int,
)

/** Durable local-only links. Removing a link never deletes either note or source data. */
interface LegacyQuickNoteMigrationLinks {
    fun list(binding: BoundUploadSession): List<LegacyQuickNoteMigrationLink>
    fun put(link: LegacyQuickNoteMigrationLink)
    fun remove(binding: BoundUploadSession, legacyNoteId: String): Boolean
    fun find(identity: LegacyQuickNoteMigrationIdentity): LegacyQuickNoteMigrationLink?
}

@Singleton
class LegacyQuickNoteMigrationStore private constructor(
    private val root: File,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : LegacyQuickNoteMigrationLinks {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "legacy-quicknote-migrations"), Unit)

    constructor(root: File) : this(root, Unit)
    private val lock = Any()

    override fun list(binding: BoundUploadSession): List<LegacyQuickNoteMigrationLink> = synchronized(lock) {
        val folder = scopeDirectory(binding)
        if (!folder.exists()) return@synchronized emptyList()
        require(folder.isDirectory) { "Migration index is not a directory" }
        val files = checkNotNull(folder.listFiles()) { "Cannot read migration index" }
        val baseFiles = files.mapNotNull { file ->
            when {
                file.isFile && file.extension == "json" -> file
                file.isFile && file.name.endsWith(".json.bak") -> File(file.parentFile, file.name.removeSuffix(".bak"))
                else -> null
            }
        }.distinctBy { it.name }
        val distinct = baseFiles.map { file -> readLink(file) }.associateBy { it.migrationKey }
        require(distinct.size == baseFiles.size) { "A different migration link already exists" }
        require(baseFiles.size <= MAX_MIGRATION_LINKS) { "Migration index exceeds the local limit" }
        distinct.values.forEach { link ->
            validate(link, binding)
            require(link.noteId == LegacyQuickNoteMigrationIdentity(binding, link.legacyNoteId).noteId)
        }
        distinct.values.sortedByDescending { it.createdAtMillis }
    }

    override fun put(link: LegacyQuickNoteMigrationLink) = synchronized(lock) {
        validate(link, BoundUploadSession(link.serverIdentity, link.ownerId))
        val folder = scopeDirectory(BoundUploadSession(link.serverIdentity, link.ownerId))
        check(folder.isDirectory || folder.mkdirs()) { "Cannot create migration index" }
        val target = linkFile(link.serverIdentity, link.ownerId, link.legacyNoteId)
        val existing = if (linkExists(target)) readLink(target) else null
        require(existing == null || existing == link) { "A different migration link already exists" }
        if (existing == null) {
            require(list(BoundUploadSession(link.serverIdentity, link.ownerId)).size < MAX_MIGRATION_LINKS) {
                "Migration index is full"
            }
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(NoteJson.encodeToString(link).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    override fun remove(binding: BoundUploadSession, legacyNoteId: String): Boolean = synchronized(lock) {
        val file = linkFile(binding.serverIdentity, binding.ownerId, legacyNoteId)
        if (!linkExists(file)) return@synchronized false
        val link = readLink(file)
        validate(link, binding)
        deleteMigrationLinkFile(file)
        true
    }

    override fun find(identity: LegacyQuickNoteMigrationIdentity): LegacyQuickNoteMigrationLink? = synchronized(lock) {
        val file = linkFile(identity.binding.serverIdentity, identity.binding.ownerId, identity.legacyNoteId)
        if (!linkExists(file)) return@synchronized null
        readLink(file).also { validate(it, identity.binding); require(it.legacyNoteId == identity.legacyNoteId) }
    }

    private fun readLink(file: File): LegacyQuickNoteMigrationLink {
        require(spoolSize(file) in 1..MAX_LINK_BYTES) {
            "Migration index entry exceeds the local limit"
        }
        return NoteJson.decodeFromString(
            AtomicFile(file).openRead().use { it.readLimited(MAX_LINK_BYTES).toString(Charsets.UTF_8) }
        )
    }

    private fun linkExists(file: File) = file.isFile || File(file.path + ".bak").isFile

    private fun spoolSize(file: File): Long = when {
        file.isFile -> file.length()
        File(file.path + ".bak").isFile -> File(file.path + ".bak").length()
        else -> 0
    }

    private fun validate(link: LegacyQuickNoteMigrationLink, binding: BoundUploadSession) {
        require(link.schemaVersion == 1 && link.serverIdentity == binding.serverIdentity && link.ownerId == binding.ownerId)
        require(link.legacyNoteId.isNotBlank() && link.legacyNoteId.length <= 128 && validNoteId(link.noteId))
        val expected = LegacyQuickNoteMigrationIdentity(binding, link.legacyNoteId).noteId
        require(link.noteId == expected) { "Migration target identity does not match its source" }
    }

    private fun scopeDirectory(binding: BoundUploadSession) = File(root, sha256("${binding.serverIdentity}\u0000${binding.ownerId}"))
    private fun linkFile(server: String, owner: String, legacyId: String) = File(
        File(root, sha256("$server\u0000$owner")), "${sha256(legacyId)}.json"
    )

    companion object { private const val MAX_LINK_BYTES = 16 * 1024 }
}

internal fun deleteMigrationLinkFile(file: File, deleteAtomicFile: (File) -> Unit = { AtomicFile(it).delete() }) {
    deleteAtomicFile(file)
    val parent = checkNotNull(file.parentFile) { "Migration index has no parent directory" }
    val remaining = checkNotNull(parent.list()) { "Cannot verify migration index removal" }
    check(file.name !in remaining && "${file.name}.bak" !in remaining) {
        "Cannot confirm migration link removal"
    }
}

@Singleton
class LegacyQuickNoteMigrationRepository @Inject constructor(
    private val transport: NativeWorkbenchTransport,
    private val notes: NoteRepository,
    private val store: LegacyQuickNoteMigrationStore,
) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow(LegacyQuickNoteMigrationState())
    val state = mutableState.asStateFlow()

    suspend fun open(requestedPage: Int = 1) {
        require(requestedPage >= 1)
        val request = generation.incrementAndGet()
        val session = transport.currentSession() ?: run {
            mutableState.value = LegacyQuickNoteMigrationState(visible = true, error = "请先登录并确认当前服务器。")
            return
        }
        mutableState.value = LegacyQuickNoteMigrationState(
            visible = true, loading = true, session = session,
        )
        try {
            val api = transport.apiFor(session)
            val response = api.getQuickNotes(page = requestedPage, pageSize = 50)
            require(response.code == 0) { response.message.ifBlank { "读取旧快速记录失败" } }
            val page = requireNotNull(response.data) { "读取旧快速记录失败：服务器未返回数据" }
            checkCurrent(session, request)
            mutableState.value = LegacyQuickNoteMigrationState(
                visible = true,
                session = session,
                page = requestedPage,
                totalPages = page.totalPages.coerceAtLeast(1),
                items = page.items.map {
                    LegacyQuickNoteSummary(it.id, it.createdAt, it.updatedAt, it.status, it.source,
                        it.contentPreview, it.attachmentCount)
                },
                links = store.list(session.binding),
            )
        } catch (error: CancellationException) {
            if (isCurrentRequest(session, request)) mutableState.value = mutableState.value.copy(loading = false)
            throw error
        } catch (error: Exception) {
            if (isCurrentRequest(session, request)) mutableState.value = LegacyQuickNoteMigrationState(
                    visible = true, session = session.takeIf(transport::isCurrent),
                    error = error.message?.take(240) ?: "读取旧快速记录失败。",
                )
        }
    }

    fun invalidateIfSessionChanged() {
        val current = mutableState.value
        val expected = current.session ?: return
        if (transport.isCurrent(expected)) return
        generation.incrementAndGet()
        mutableState.value = LegacyQuickNoteMigrationState(
            visible = current.visible,
            error = "账号或服务器已切换；旧预览已清除，请重新打开迁移页。",
        )
    }

    suspend fun openForSession(session: NativeWorkbenchSession, requestedPage: Int = 1) {
        require(requestedPage >= 1)
        check(transport.isCurrent(session)) { "账号或服务器已变化；未加载旧记录。" }
        val request = generation.incrementAndGet()
        mutableState.value = LegacyQuickNoteMigrationState(visible = true, loading = true, session = session)
        try {
            val response = transport.apiFor(session).getQuickNotes(page = requestedPage, pageSize = 50)
            require(response.code == 0) { response.message.ifBlank { "读取旧快速记录失败" } }
            val page = requireNotNull(response.data) { "读取旧快速记录失败：服务器未返回数据" }
            checkCurrent(session, request)
            mutableState.value = LegacyQuickNoteMigrationState(
                visible = true,
                session = session,
                page = requestedPage,
                totalPages = page.totalPages.coerceAtLeast(1),
                items = page.items.map {
                    LegacyQuickNoteSummary(it.id, it.createdAt, it.updatedAt, it.status, it.source,
                        it.contentPreview, it.attachmentCount)
                },
                links = store.list(session.binding),
            )
        } catch (error: CancellationException) {
            if (isCurrentRequest(session, request)) mutableState.value = mutableState.value.copy(loading = false)
            throw error
        } catch (error: Exception) {
            if (isCurrentRequest(session, request)) mutableState.value = LegacyQuickNoteMigrationState(
                visible = true,
                session = session.takeIf(transport::isCurrent),
                error = error.message?.take(240) ?: "读取旧快速记录失败。",
            )
        }
    }

    suspend fun reopenMappedNote(noteId: String): String = mutex.withLock {
        val request = generation.incrementAndGet()
        val session = requireSession()
        val link = store.list(session.binding).singleOrNull { it.noteId == noteId }
            ?: error("该本机笔记与当前账号/服务器的迁移映射不匹配")
        checkCurrent(session, request)
        link.noteId
    }

    suspend fun select(legacyNoteId: String) = mutex.withLock {
        val request = generation.incrementAndGet()
        val session = requireSession()
        require(mutableState.value.items.any { it.id == legacyNoteId }) { "该旧记录不在当前已加载列表中" }
        mutableState.value = mutableState.value.copy(loading = true, error = null, selected = null)
        try {
            val response = transport.apiFor(session).getQuickNote(legacyNoteId)
            require(response.code == 0) { response.message.ifBlank { "读取旧记录详情失败" } }
            val detail = requireNotNull(response.data) { "读取旧记录详情失败：服务器未返回数据" }
            require(detail.id == legacyNoteId) { "服务器返回了不同的旧记录" }
            checkCurrent(session, request)
            mutableState.value = mutableState.value.copy(loading = false, selected = detail)
        } catch (error: CancellationException) {
            if (isCurrentRequest(session, request)) mutableState.value = mutableState.value.copy(loading = false)
            throw error
        } catch (error: Exception) {
            if (isCurrentRequest(session, request)) mutableState.value = mutableState.value.copy(
                loading = false,
                error = error.message?.take(240) ?: "读取旧记录详情失败。",
            )
        }
    }

    suspend fun confirm(): String = mutex.withLock {
        val request = generation.incrementAndGet()
        val session = requireSession()
        val preview = requireNotNull(mutableState.value.selected) { "请先打开并核对旧记录预览" }
        mutableState.value = mutableState.value.copy(confirming = true, error = null, feedback = null)
        try {
            val response = transport.apiFor(session).getQuickNote(preview.id)
            require(response.code == 0) { response.message.ifBlank { "重新核对旧记录失败" } }
            val fresh = requireNotNull(response.data) { "重新核对旧记录失败：服务器未返回数据" }
            require(fresh.id == preview.id) { "服务器返回了不同的旧记录" }
            checkCurrent(session, request)
            if (fresh != preview) {
                mutableState.value = mutableState.value.copy(
                    confirming = false,
                    selected = fresh,
                    error = "预览期间旧记录已变化；请重新核对后再确认。",
                )
                error("旧记录已变化，请核对更新后的预览")
            }
            validateMigrationSize(fresh)
            val identity = LegacyQuickNoteMigrationIdentity(session.binding, fresh.id)
            val document = notes.importLegacyQuickNote(
                identity = identity,
                detail = fresh,
                mappings = store,
                checkActive = { checkCurrent(session, request) },
            )
            checkCurrent(session, request)
            mutableState.value = mutableState.value.copy(
                confirming = false,
                selected = fresh,
                migratedNoteId = document.id,
                links = store.list(session.binding),
                feedback = "已在本机创建原生笔记；旧记录未修改，附件仅保留引用。",
            )
            document.id
        } catch (error: CancellationException) {
            if (isCurrentRequest(session, request)) {
                mutableState.value = mutableState.value.copy(confirming = false)
            }
            throw error
        } catch (error: Exception) {
            if (isCurrentRequest(session, request)) {
                mutableState.value = mutableState.value.copy(
                    confirming = false,
                    error = error.message?.take(240) ?: "本机迁移失败；旧记录与已保存材料未更改，可安全重试。",
                )
            }
            throw error
        }
    }

    suspend fun revokeLink(legacyNoteId: String, expectedSession: NativeWorkbenchSession) = mutex.withLock {
        val request = generation.incrementAndGet()
        val session = requireSession()
        require(session == expectedSession) { "迁移页面账号或服务器已切换；未撤回任何映射。" }
        // Revalidate immediately before the durable write; an account change must not
        // let a stale confirmation remove a mapping after it was queued.
        checkCurrent(session, request)
        store.remove(session.binding, legacyNoteId)
        checkCurrent(session, request)
        mutableState.value = mutableState.value.copy(
            links = store.list(session.binding),
            migratedNoteId = null,
            feedback = "已撤回本机映射；原快速记录和已迁移笔记均未删除。",
            error = null,
        )
    }

    suspend fun loadPage(page: Int) {
        val current = mutableState.value
        require(current.visible && page in 1..current.totalPages)
        open(page)
    }

    fun dismissPreview() {
        if (!mutableState.value.confirming && mutableState.value.visible) {
            generation.incrementAndGet()
            mutableState.value = mutableState.value.copy(loading = false, selected = null, error = null)
        }
    }

    fun close() { generation.incrementAndGet(); mutableState.value = LegacyQuickNoteMigrationState() }

    private fun requireSession(): NativeWorkbenchSession {
        val expected = requireNotNull(mutableState.value.session) { "迁移页面未绑定到已加载账号" }
        checkCurrent(expected, generation.get())
        return expected
    }

    private fun checkCurrent(session: NativeWorkbenchSession, request: Long) {
        if (generation.get() != request || mutableState.value.session != session || !mutableState.value.visible ||
            !transport.isCurrent(session)
        ) error("账号、服务器或迁移页面已变化；没有向新会话应用旧响应，请重新打开迁移页。")
    }

    private fun isCurrentRequest(session: NativeWorkbenchSession, request: Long): Boolean =
        generation.get() == request && mutableState.value.session == session &&
            mutableState.value.visible && transport.isCurrent(session)
}

data class LegacyQuickNoteSource(
    val identity: LegacyQuickNoteMigrationIdentity,
    val detail: QuickNoteDetailDto,
    val importedAtMillis: Long,
) {
    fun block(): JsonObject {
        val binding = identity.binding
        return buildJsonObject {
            put("id", JsonPrimitive(UUIDFromDigest("${identity.stableKey}:source-block")))
            put("kind", JsonPrimitive("legacyQuickNote"))
            put("serverIdentity", JsonPrimitive(binding.serverIdentity))
            put("ownerId", JsonPrimitive(binding.ownerId))
            put("legacyNoteId", JsonPrimitive(detail.id))
            put("source", JsonPrimitive(detail.source))
            put("status", JsonPrimitive(detail.status))
            put("createdAt", JsonPrimitive(detail.createdAt))
            put("updatedAt", JsonPrimitive(detail.updatedAt))
            put("archivedAt", detail.archivedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("rawContentMarkdown", JsonPrimitive(detail.contentMarkdown))
            put("rawMetadataJson", JsonPrimitive(detail.metadataJson))
            put("importedAtMillis", JsonPrimitive(importedAtMillis))
            put("attachmentsCopied", JsonPrimitive(false))
            put("attachments", JsonArray(detail.attachments.map(QuickNoteAttachmentDto::toMigrationReference)))
        }
    }
}

internal fun QuickNoteAttachmentDto.toMigrationReference() = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("fileName", JsonPrimitive(fileName))
    put("contentType", JsonPrimitive(contentType))
    put("sizeBytes", JsonPrimitive(sizeBytes))
    put("createdAt", JsonPrimitive(createdAt))
}

internal fun validateMigrationSize(detail: QuickNoteDetailDto) {
    require(detail.contentMarkdown.toByteArray(Charsets.UTF_8).size <= MAX_LEGACY_CONTENT_BYTES) {
        "旧记录正文超过本机迁移上限；旧记录保留且未创建副本。"
    }
    require(detail.metadataJson.toByteArray(Charsets.UTF_8).size <= MAX_LEGACY_METADATA_BYTES) {
        "旧记录 metadata 超过本机迁移上限；旧记录保留且未创建副本。"
    }
    require(detail.attachments.size <= MAX_LEGACY_ATTACHMENTS) { "附件引用数量超过本机迁移上限；旧记录未修改。" }
    detail.attachments.forEach {
        require(it.id.isNotBlank() && it.id.length <= 128 && it.fileName.length <= 512 &&
            it.contentType.length <= 256 && it.sizeBytes >= 0
        ) { "附件引用信息不完整；旧记录未修改。" }
    }
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun UUIDFromDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun java.io.InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "Migration index exceeds the local limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
