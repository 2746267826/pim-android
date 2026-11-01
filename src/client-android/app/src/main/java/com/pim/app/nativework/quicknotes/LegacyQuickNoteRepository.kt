package com.pim.app.nativework.quicknotes

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

/**
 * Metadata JSON is deliberately not sent through UpdateQuickNoteRequest because
 * the contract has no such field. Existing attachment ids are retained so editing
 * the text cannot detach or overwrite a legacy note's files and identity.
 */
fun legacyQuickNoteUpdateRequest(
    detail: QuickNoteDetailDto,
    contentMarkdown: String,
    status: String = detail.status,
): UpdateQuickNoteRequest = UpdateQuickNoteRequest(
    attachmentIds = detail.attachments.map { it.id },
    contentMarkdown = contentMarkdown,
    status = status,
)

/**
 * MIG04 / NOTE03 compatibility boundary for old quick HTML notes.
 *
 * The legacy record itself remains authoritative on the server. This client
 * edits text/status through the contract and references existing attachment ids;
 * it never rewrites metadataJson or claims to replace the old HTML record.
 */
@Singleton
class LegacyQuickNoteRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun load(search: String? = null): LegacyQuickNotePage {
        val result = api.getQuickNotes(
            search = search?.trim()?.takeIf { it.isNotEmpty() },
            page = 1,
            pageSize = 50,
        )
        val page = requireData(result.data, result.message, "读取快速记录")
        return LegacyQuickNotePage(page.items, page.totalCount, page.totalPages)
    }

    suspend fun get(id: String): QuickNoteDetailDto =
        requireData(api.getQuickNote(id).data, "快速记录不存在", "读取快速记录")

    suspend fun create(contentMarkdown: String): QuickNoteDetailDto {
        require(contentMarkdown.isNotBlank()) { "快速记录内容不能为空" }
        val result = api.createQuickNote(
            CreateQuickNoteRequest(contentMarkdown = contentMarkdown)
        )
        return requireData(result.data, result.message, "新建快速记录")
    }

    suspend fun update(detail: QuickNoteDetailDto, contentMarkdown: String): QuickNoteDetailDto {
        require(contentMarkdown.isNotBlank()) { "快速记录内容不能为空" }
        val result = api.updateQuickNote(
            detail.id,
            legacyQuickNoteUpdateRequest(detail, contentMarkdown)
        )
        return requireData(result.data, result.message, "修改快速记录")
    }

    suspend fun archive(detail: QuickNoteDetailDto): QuickNoteDetailDto =
        requireData(api.archiveQuickNote(detail.id).data, "归档失败", "归档快速记录")

    suspend fun process(detail: QuickNoteDetailDto): QuickNoteDetailDto =
        requireData(api.processQuickNote(detail.id).data, "处理失败", "处理快速记录")

    suspend fun restore(detail: QuickNoteDetailDto): QuickNoteDetailDto =
        requireData(
            api.restoreQuickNote(
                detail.id,
                RestoreQuickNoteRequest(status = "inbox")
            ).data,
            "恢复失败",
            "恢复快速记录",
        )

    suspend fun delete(detail: QuickNoteDetailDto) {
        val result = api.deleteQuickNote(detail.id)
        requireData(result.data, result.message, "删除快速记录")
    }

    private fun <T> requireData(value: T?, message: String, operation: String): T =
        value ?: error(
            message.trim().takeIf { it.isNotEmpty() }
                ?.let { "$operation：$it" }
                ?: "$operation：服务端未返回数据"
        )
}
