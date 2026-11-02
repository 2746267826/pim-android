package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import java.io.File
import kotlinx.serialization.encodeToString

/** Small, owner/server/source-bound store for replay identifiers and editable drafts. */
class NoteTaskSuggestionStore(private val root: File) {
    fun read(source: NoteTaskSuggestionSource, binding: BoundUploadSession): NoteTaskSuggestionScopeRecord =
        synchronized(lock) {
            val file = file(source, binding)
            if (!materialFileExists(file)) return@synchronized newRecord(source, binding)
            require(file.length() <= MAX_RECORD_BYTES && File(file.path + ".bak").length() <= MAX_RECORD_BYTES) {
                "本机任务建议记录超过读取预算；保留原文件并暂停写入"
            }
            val record = NoteJson.decodeFromString<NoteTaskSuggestionScopeRecord>(readMaterialFile(file))
            validate(record, source, binding)
            record
        }

    fun freezeExtraction(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        request: com.pim.core.contract.StartNoteTaskSuggestionExtractionRequest,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionScopeRecord = update(source, binding, nowMillis) { record ->
        val current = record.frozenExtraction
        require(current == null || current == request) {
            "已有提取请求未核对；必须复用原 requestId 和冻结目标"
        }
        record.copy(frozenExtraction = request)
    }

    fun recordExtraction(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        requestId: String,
        extractionId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionScopeRecord = update(source, binding, nowMillis) { record ->
        require(record.frozenExtraction?.requestId == requestId) { "提取回执不匹配本机冻结请求" }
        record.copy(frozenExtraction = null, extractionId = extractionId)
    }

    fun draft(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        suggestionId: String,
    ): NoteTaskSuggestionDraft? = read(source, binding).drafts.singleOrNull { it.suggestionId == suggestionId }

    fun putDraft(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        draft: NoteTaskSuggestionDraft,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionScopeRecord = update(source, binding, nowMillis) { record ->
        val old = record.drafts.firstOrNull { it.suggestionId == draft.suggestionId }
        require(old == null || old.expectedRevision == draft.expectedRevision) {
            "编辑基线发生变化；保留旧稿并要求人工核对"
        }
        require(old != null || record.drafts.size < MAX_DRAFTS) {
            "本机待处理建议已达到数量上限；未淘汰任何已有草稿"
        }
        val updated = draft.copy(updatedAtMillis = nowMillis)
        record.copy(drafts = (record.drafts.filterNot { it.suggestionId == draft.suggestionId } + updated)
            .sortedByDescending { it.updatedAtMillis })
    }

    /** Applies a server CAS receipt only if the exact submitted local revision remains stored. */
    fun recordSavedDraft(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        submitted: NoteTaskSuggestionDraft,
        saved: NoteTaskSuggestionDraft,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionScopeRecord = update(source, binding, nowMillis) { record ->
        val current = record.drafts.singleOrNull { it.suggestionId == submitted.suggestionId }
            ?: error("本机修订已变化；服务器回执未覆盖当前草稿")
        require(current == submitted && submitted.dirty && submitted.confirmRequestId == null &&
            saved.suggestionId == submitted.suggestionId && saved.expectedRevision.isNotBlank() &&
            saved.expectedRevision.length <= 512 && !saved.dirty && saved.confirmRequestId == null &&
            saved.confirmRevision == null) {
            "服务器保存回执与本机提交修订不匹配；当前草稿保留"
        }
        record.copy(drafts = record.drafts.map {
            if (it.suggestionId == submitted.suggestionId) saved.copy(updatedAtMillis = nowMillis) else it
        })
    }

    /** Drops only clean, non-pending drafts once an authoritative list has been read. */
    fun reconcileDrafts(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        remote: List<NoteTaskSuggestionDto>,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionScopeRecord = update(source, binding, nowMillis) { record ->
        val byId = remote.associateBy { it.id }
        record.copy(drafts = record.drafts.filter { draft ->
            if (draft.dirty) true
            else if (draft.confirmRequestId == null) false
            else byId[draft.suggestionId]?.status == NoteTaskSuggestionStatusDto.DRAFT ||
                byId[draft.suggestionId] == null
        })
    }

    fun rebaseDraft(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        draft: NoteTaskSuggestionDraft,
        newExpectedRevision: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionDraft {
        require(newExpectedRevision.isNotBlank() && newExpectedRevision.length <= 512)
        return update(source, binding, nowMillis) { record ->
            val existing = record.drafts.singleOrNull { it.suggestionId == draft.suggestionId }
                ?: error("本机修订已不存在")
            require(existing == draft && existing.expectedRevision != newExpectedRevision &&
                existing.dirty && existing.confirmRequestId == null) {
                "本机修订或服务端冲突基线已变化；未覆盖草稿"
            }
            record.copy(drafts = record.drafts.map {
                if (it.suggestionId == draft.suggestionId) it.copy(
                    expectedRevision = newExpectedRevision,
                    dirty = true,
                    updatedAtMillis = nowMillis,
                ) else it
            })
        }.drafts.single { it.suggestionId == draft.suggestionId }
    }

    fun removeDraft(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        suggestionId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteTaskSuggestionScopeRecord = update(source, binding, nowMillis) { record ->
        record.copy(drafts = record.drafts.filterNot { it.suggestionId == suggestionId })
    }

    private fun update(
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
        nowMillis: Long,
        change: (NoteTaskSuggestionScopeRecord) -> NoteTaskSuggestionScopeRecord,
    ): NoteTaskSuggestionScopeRecord = synchronized(lock) {
        require(nowMillis >= 0)
        val before = read(source, binding)
        val after = change(before).copy(updatedAtMillis = nowMillis)
        validate(after, source, binding)
        val json = NoteJson.encodeToString(after)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) {
            "本机保留的任务建议草稿已达到空间上限；现有内容未被覆盖"
        }
        writeVerifiedMaterialJson(file(source, binding), json)
        after
    }

    private fun validate(
        record: NoteTaskSuggestionScopeRecord,
        source: NoteTaskSuggestionSource,
        binding: BoundUploadSession,
    ) {
        require(record.schemaVersion == 1 && record.serverIdentity == binding.serverIdentity &&
            record.ownerId == binding.ownerId && record.noteId == source.noteId &&
            record.materialVersionId == source.materialVersionId && record.artifactId == source.artifactId &&
            record.versionId == source.versionId && record.contentSha256.equals(source.contentSha256, true) &&
            record.inputSha256.equals(source.inputSha256, true) &&
            record.manifestSha256.equals(source.manifestSha256, true) &&
            record.generationTaskId == source.generationTaskId) {
            "本机任务建议意图与当前账号、服务器或固定来源不匹配；已锁定"
        }
        require(record.updatedAtMillis >= 0 && record.drafts.size <= MAX_DRAFTS &&
            record.drafts.map { it.suggestionId }.distinct().size == record.drafts.size)
        record.frozenExtraction?.let { request ->
            require(request.expectedUserId == binding.ownerId && request.noteId == source.noteId &&
                request.artifactId == source.artifactId && request.versionId == source.versionId &&
                request.expectedContentSha256.equals(source.contentSha256, true) &&
                request.acknowledgePossibleCharge && request.consent.confirmed)
        }
        require(record.extractionId == null || validNoteId(record.extractionId))
        record.drafts.forEach { draft ->
            require(validNoteId(draft.suggestionId) && draft.expectedRevision.isNotBlank() &&
                draft.expectedRevision.length <= 512 && draft.title.length <= MAX_TITLE_CHARS &&
                draft.description.length <= MAX_DESCRIPTION_CHARS && draft.priority in PRIORITIES &&
                draft.due.length <= MAX_DUE_CHARS && draft.updatedAtMillis >= 0)
            require((draft.confirmRequestId == null) == (draft.confirmRevision == null))
            draft.confirmRequestId?.let { require(validNoteId(it) && draft.confirmRevision == draft.expectedRevision) }
        }
        record.frozenExtraction?.let { request ->
            require(validNoteId(request.requestId) && request.consent.modelId.isNotBlank() &&
                request.consent.modelRevision.isNotBlank() && request.consent.endpoint.length <= 2_000)
        }
    }

    private fun newRecord(source: NoteTaskSuggestionSource, binding: BoundUploadSession) =
        NoteTaskSuggestionScopeRecord(
            serverIdentity = binding.serverIdentity,
            ownerId = binding.ownerId,
            noteId = source.noteId,
            materialVersionId = source.materialVersionId,
            artifactId = source.artifactId,
            versionId = source.versionId,
            contentSha256 = source.contentSha256.lowercase(),
            inputSha256 = source.inputSha256.lowercase(),
            manifestSha256 = source.manifestSha256.lowercase(),
            generationTaskId = source.generationTaskId,
            updatedAtMillis = System.currentTimeMillis(),
        )

    private fun file(source: NoteTaskSuggestionSource, binding: BoundUploadSession): File {
        val identity = listOf(
            binding.serverIdentity, binding.ownerId, source.noteId, source.materialVersionId,
            source.artifactId, source.versionId, source.contentSha256, source.inputSha256,
            source.manifestSha256, source.generationTaskId.orEmpty(),
        ).joinToString("\n")
        return File(root, "${materialDigest(identity)}.json")
    }

    companion object {
        const val MAX_DRAFTS = 100
        const val MAX_RECORD_BYTES = 1_048_576
        const val MAX_TITLE_CHARS = 255
        const val MAX_DESCRIPTION_CHARS = 20_000
        const val MAX_DUE_CHARS = 100
        const val MIN_PRIORITY = 1
        const val MAX_PRIORITY = 9
        private val PRIORITIES = setOf(MIN_PRIORITY, 5, MAX_PRIORITY)
        private val lock = Any()

        fun at(noBackupDirectory: File) = NoteTaskSuggestionStore(File(noBackupDirectory, "note-task-suggestions"))
    }
}
