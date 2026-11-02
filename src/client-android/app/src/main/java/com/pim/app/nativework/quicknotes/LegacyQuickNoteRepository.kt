package com.pim.app.nativework.quicknotes

import com.pim.app.nativework.NativeWorkbenchRequestException
import com.pim.core.contract.CreateQuickNoteRequest
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.QuickNoteListItemDto
import com.pim.core.contract.RestoreQuickNoteRequest
import com.pim.core.contract.UpdateQuickNoteRequest
import com.pim.core.network.ApiService
import javax.inject.Inject
import javax.inject.Singleton

data class LegacyQuickNotePage(
    val items: List<QuickNoteListItemDto>,
    val totalCount: Int,
    val totalPages: Int,
)

data class QuickNoteDraft(
    val baseline: String,
    val content: String,
) {
    val isDirty: Boolean get() = content != baseline

    fun edit(value: String): QuickNoteDraft = copy(content = value)

    /**
     * Rebase only when the user has no pending edit, or when the server now
     * contains exactly the submitted draft. A failed save or an unrelated
     * refresh must never erase local text.
     */
    fun onServerDetailChanged(serverContent: String): QuickNoteDraft = when {
        content == serverContent -> QuickNoteDraft(serverContent, serverContent)
        !isDirty -> QuickNoteDraft(serverContent, serverContent)
        else -> this
    }
}

/**
 * `source=quick-html` is emitted by the legacy quick-note capture path.
 * `legacyHtml` is also persisted in metadata for older records. The update
 * contract has only `contentMarkdown`; treating either as plain text would
 * overwrite the original HTML representation.
 */
fun QuickNoteDetailDto.isLegacyQuickNoteHtml(): Boolean =
    source.trim().lowercase() in setOf("quick-html", "quick_html") ||
        metadataJson.contains("\"legacyHtml\"", ignoreCase = true) ||
        attachments.any {
            it.contentType.equals("text/html", ignoreCase = true) ||
                it.fileName.endsWith(".html", ignoreCase = true) ||
                it.fileName.endsWith(".htm", ignoreCase = true)
        } ||
        contentMarkdown.trimStart().startsWith("<")

/**
 * Metadata JSON is deliberately not sent through UpdateQuickNoteRequest because
 * the contract has no such field. Existing attachment ids are retained so editing
 * the text cannot detach or overwrite a legacy note's files and identity.
 */
fun legacyQuickNoteUpdateRequest(
    detail: QuickNoteDetailDto,
    contentMarkdown: String,
    status: String = detail.status,
): UpdateQuickNoteRequest {
    require(!detail.isLegacyQuickNoteHtml()) {
        "旧 HTML 快速记录为只读；可用处理/归档/恢复/删除动作，正文不会转换成纯文本覆盖。"
    }
    return UpdateQuickNoteRequest(
        attachmentIds = detail.attachments.map { it.id },
        contentMarkdown = contentMarkdown,
        status = status,
    )
}

/**
 * MIG04 / NOTE03 compatibility boundary for old quick HTML notes.
 *
 * The legacy record itself remains authoritative on the server. Ordinary quick
 * notes edit text/status through the contract and reference existing attachment
 * ids. Legacy HTML records allow lifecycle actions only, because the update
 * contract has no HTML/metadata field and would overwrite `contentMarkdown`.
 */
@Singleton
class LegacyQuickNoteRepository @Inject constructor(
    private val api: ApiService,
) {
    fun boundTo(boundApi: ApiService): LegacyQuickNoteRepository =
        LegacyQuickNoteRepository(boundApi)

    suspend fun load(search: String? = null): LegacyQuickNotePage {
        val result = api.getQuickNotes(
            search = search?.trim()?.takeIf { it.isNotEmpty() },
            page = 1,
            pageSize = 50,
        )
        val page = requireResponse(result.code, result.data, result.message, "读取快速记录")
        return LegacyQuickNotePage(page.items, page.totalCount, page.totalPages)
    }

    suspend fun get(id: String): QuickNoteDetailDto {
        val result = api.getQuickNote(id)
        return requireResponse(result.code, result.data, result.message, "读取快速记录", "快速记录不存在")
    }

    suspend fun create(contentMarkdown: String): QuickNoteDetailDto {
        require(contentMarkdown.isNotBlank()) { "快速记录内容不能为空" }
        val result = api.createQuickNote(
            CreateQuickNoteRequest(contentMarkdown = contentMarkdown)
        )
        return requireResponse(result.code, result.data, result.message, "新建快速记录")
    }

    suspend fun update(detail: QuickNoteDetailDto, contentMarkdown: String): QuickNoteDetailDto {
        require(contentMarkdown.isNotBlank()) { "快速记录内容不能为空" }
        val result = api.updateQuickNote(
            detail.id,
            legacyQuickNoteUpdateRequest(detail, contentMarkdown)
        )
        return requireResponse(result.code, result.data, result.message, "修改快速记录")
    }

    suspend fun archive(detail: QuickNoteDetailDto): QuickNoteDetailDto {
        val result = api.archiveQuickNote(detail.id)
        return requireResponse(result.code, result.data, result.message, "归档快速记录", "归档失败")
    }

    suspend fun process(detail: QuickNoteDetailDto): QuickNoteDetailDto {
        val result = api.processQuickNote(detail.id)
        return requireResponse(result.code, result.data, result.message, "处理快速记录", "处理失败")
    }

    suspend fun restore(detail: QuickNoteDetailDto): QuickNoteDetailDto {
        val result = api.restoreQuickNote(
            detail.id,
            RestoreQuickNoteRequest(status = "inbox")
        )
        return requireResponse(result.code, result.data, result.message, "恢复快速记录", "恢复失败")
    }

    suspend fun delete(detail: QuickNoteDetailDto) {
        val result = api.deleteQuickNote(detail.id)
        requireResponse(result.code, result.data, result.message, "删除快速记录")
    }

    private fun <T> requireResponse(
        code: Int,
        value: T?,
        message: String,
        operation: String,
        emptyFallback: String = "服务端未返回数据",
    ): T {
        val detail = message.trim().takeIf(String::isNotEmpty)
        if (code != 0) {
            throw NativeWorkbenchRequestException(detail?.let { "$operation：$it" } ?: "$operation：请求失败（code=$code）")
        }
        return value ?: throw NativeWorkbenchRequestException("$operation：$emptyFallback")
    }
}
