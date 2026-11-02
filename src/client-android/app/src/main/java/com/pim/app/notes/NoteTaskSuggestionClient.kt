package com.pim.app.notes

import com.pim.core.contract.ConfirmNoteTaskSuggestionRequest
import com.pim.core.contract.DismissNoteTaskSuggestionRequest
import com.pim.core.contract.NoteDerivedVersionDto
import com.pim.core.contract.NoteDerivedVersionDtoApiResponse
import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionExtractionDto
import com.pim.core.contract.NoteTaskSuggestionExtractionDtoApiResponse
import com.pim.core.contract.NoteTaskSuggestionListDtoApiResponse
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import com.pim.core.contract.StartNoteTaskSuggestionExtractionRequest
import com.pim.core.contract.UpdateNoteTaskSuggestionRequest
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.io.IOException

interface NoteTaskSuggestionTransport {
    suspend fun verifySource(source: NoteTaskSuggestionSource): NoteTaskSuggestionSourceContent
    suspend fun destinations(): List<NoteTaskSuggestionDestination>
    suspend fun list(source: NoteTaskSuggestionSource): List<NoteTaskSuggestionDto>
    suspend fun extraction(source: NoteTaskSuggestionSource, id: String): NoteTaskSuggestionExtractionDto
    suspend fun startExtraction(request: StartNoteTaskSuggestionExtractionRequest): NoteTaskSuggestionExtractionDto
    suspend fun update(source: NoteTaskSuggestionSource, id: String, request: UpdateNoteTaskSuggestionRequest): NoteTaskSuggestionDto
    suspend fun dismiss(source: NoteTaskSuggestionSource, item: NoteTaskSuggestionDto): NoteTaskSuggestionDto
    suspend fun confirm(source: NoteTaskSuggestionSource, item: NoteTaskSuggestionDto, requestId: String): NoteTaskSuggestionDto
}

class NoteTaskSuggestionClient(
    private val api: ApiService,
    private val binding: BoundUploadSession,
) : NoteTaskSuggestionTransport {
    override suspend fun verifySource(source: NoteTaskSuggestionSource): NoteTaskSuggestionSourceContent {
        require(source.noteId.isNotBlank() && source.materialVersionId.isNotBlank())
        val version = versionData(api.getNoteDerivedVersion(source.artifactId, source.versionId))
        require(version.artifactId == source.artifactId && version.versionId == source.versionId &&
            version.sourceVersionId == source.materialVersionId) { "固定派生版本与原材料版本不匹配" }
        require(version.contentSha256.equals(source.contentSha256, true) &&
            version.inputSha256.equals(source.inputSha256, true) &&
            version.manifestSha256.equals(source.manifestSha256, true) &&
            version.generationTaskId == source.generationTaskId) { "固定来源摘要已变化；未发送提取请求" }
        require(materialDigest(version.contentMarkdown) == source.contentSha256.lowercase()) {
            "固定派生正文与服务端内容摘要不匹配；未发送提取请求"
        }
        return NoteTaskSuggestionSourceContent(source, version.contentMarkdown)
    }

    override suspend fun destinations(): List<NoteTaskSuggestionDestination> {
        val response = api.getNoteDerivedDestinations()
        if (response.code != 0) throw NoteTaskSuggestionRequestRejectedException(response.message)
        val data = response.data ?: throw IOException("服务器未返回文本目标目录")
        require(data.total >= 0 && data.total == data.items.size)
        val textTargets = data.items.filter { it.capabilities.any { capability -> capability.equals("Text", true) } }
        require(textTargets.map { "${it.modelId}:${it.modelRevision}" }.distinct().size == textTargets.size)
        return textTargets.map {
            require(validNoteId(it.modelId) && validNoteId(it.modelRevision) && it.displayName.isNotBlank() &&
                it.model.isNotBlank() && it.endpoint.isTrustedSuggestionEndpoint())
            NoteTaskSuggestionDestination(
                modelId = it.modelId,
                modelRevision = it.modelRevision,
                displayName = it.displayName.take(200),
                model = it.model.take(200),
                endpoint = it.endpoint,
            )
        }
    }

    override suspend fun list(source: NoteTaskSuggestionSource): List<NoteTaskSuggestionDto> {
        val response = api.listNoteTaskSuggestions(source.noteId)
        if (response.code != 0) throw NoteTaskSuggestionRequestRejectedException(response.message)
        val data = response.data ?: throw IOException("服务器未返回任务建议")
        require(data.total >= 0 && data.total == data.items.size &&
            data.items.map { it.id }.distinct().size == data.items.size) { "任务建议列表不完整或包含重复项" }
        return data.items.onEach { require(it.noteId == source.noteId) { "服务器返回其他笔记的建议" } }
            .filter { it.versionId == source.versionId }
            .onEach { it.requireSource(source) }
    }

    override suspend fun extraction(source: NoteTaskSuggestionSource, id: String): NoteTaskSuggestionExtractionDto {
        require(validNoteId(id))
        return extractionData(api.getNoteTaskSuggestionExtraction(id)).also { it.requireSource(source) }
    }

    override suspend fun startExtraction(
        request: StartNoteTaskSuggestionExtractionRequest,
    ): NoteTaskSuggestionExtractionDto = extractionData(api.startNoteTaskSuggestionExtraction(request)).also {
        require(it.requestId == request.requestId)
        require(it.noteId == request.noteId && it.artifactId == request.artifactId &&
            it.versionId == request.versionId &&
            it.sourceContentSha256.equals(request.expectedContentSha256, true)) {
            "提取回执绑定了其他固定笔记来源"
        }
        require(it.modelProfileId == request.consent.modelId && it.modelRevision == request.consent.modelRevision &&
            it.modelEndpoint == request.consent.endpoint) { "提取回执使用了不同的冻结目标" }
    }

    override suspend fun update(
        source: NoteTaskSuggestionSource,
        id: String,
        request: UpdateNoteTaskSuggestionRequest,
    ): NoteTaskSuggestionDto = suggestionData(api.updateNoteTaskSuggestion(id, request)).also {
        it.requireSource(source)
        require(it.id == id && it.revision.isNotBlank())
    }

    override suspend fun dismiss(
        source: NoteTaskSuggestionSource,
        item: NoteTaskSuggestionDto,
    ): NoteTaskSuggestionDto = suggestionData(
        api.dismissNoteTaskSuggestion(item.id, DismissNoteTaskSuggestionRequest(expectedRevision = item.revision)),
    ).also {
        it.requireSource(source)
        require(it.id == item.id && it.status == NoteTaskSuggestionStatusDto.DISMISSED)
    }

    override suspend fun confirm(
        source: NoteTaskSuggestionSource,
        item: NoteTaskSuggestionDto,
        requestId: String,
    ): NoteTaskSuggestionDto = suggestionData(
        api.confirmNoteTaskSuggestion(item.id, ConfirmNoteTaskSuggestionRequest(
            expectedRevision = item.revision,
            requestId = requestId,
        )),
    ).also {
        it.requireSource(source)
        require(it.id == item.id && it.status == NoteTaskSuggestionStatusDto.CONFIRMED &&
            !it.taskId.isNullOrBlank()) { "确认回执未包含已创建的任务" }
    }

    private fun NoteTaskSuggestionDto.requireSource(source: NoteTaskSuggestionSource) {
        require(noteId == source.noteId && artifactId == source.artifactId && versionId == source.versionId &&
            sourceContentSha256.equals(source.contentSha256, true) &&
            sourceInputSha256.equals(source.inputSha256, true) &&
            sourceManifestSha256.equals(source.manifestSha256, true) &&
            sourceGenerationTaskId == source.generationTaskId) { "服务器建议属于其他笔记或固定版本" }
    }

    private fun NoteTaskSuggestionExtractionDto.requireSource(source: NoteTaskSuggestionSource) {
        require(noteId == source.noteId && artifactId == source.artifactId && versionId == source.versionId &&
            sourceContentSha256.equals(source.contentSha256, true) &&
            sourceInputSha256.equals(source.inputSha256, true) &&
            sourceManifestSha256.equals(source.manifestSha256, true) &&
            sourceGenerationTaskId == source.generationTaskId) { "服务器提取记录属于其他笔记或固定版本" }
    }

    private fun suggestionData(response: com.pim.core.contract.NoteTaskSuggestionDtoApiResponse): NoteTaskSuggestionDto {
        if (response.code != 0) throw NoteTaskSuggestionRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回任务建议回执")
    }

    private fun extractionData(response: NoteTaskSuggestionExtractionDtoApiResponse): NoteTaskSuggestionExtractionDto {
        if (response.code != 0) throw NoteTaskSuggestionRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回提取回执")
    }

    private fun versionData(response: NoteDerivedVersionDtoApiResponse): NoteDerivedVersionDto {
        if (response.code != 0) throw NoteTaskSuggestionRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回固定派生版本")
    }

    private fun String.isTrustedSuggestionEndpoint(): Boolean {
        val uri = runCatching { java.net.URI(this) }.getOrNull() ?: return false
        return uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    companion object {
        fun sourceFrom(
            noteId: String,
            materialVersionId: String,
            version: NoteArtifactVersionProjection,
            title: String = "",
        ) = NoteTaskSuggestionSource(
            noteId = noteId,
            materialVersionId = materialVersionId,
            artifactId = version.artifactId,
            versionId = version.versionId,
            contentSha256 = version.contentSha256,
            inputSha256 = version.inputSha256,
            manifestSha256 = version.manifestSha256,
            generationTaskId = version.generationTaskId,
            title = title,
        )

        fun sourceFrom(
            noteId: String,
            materialVersionId: String,
            version: com.pim.core.contract.NoteDerivedVersionDto,
            title: String = "",
        ) = NoteTaskSuggestionSource(
            noteId = noteId,
            materialVersionId = materialVersionId,
            artifactId = version.artifactId,
            versionId = version.versionId,
            contentSha256 = version.contentSha256,
            inputSha256 = version.inputSha256,
            manifestSha256 = version.manifestSha256,
            generationTaskId = version.generationTaskId,
            title = title,
        )
    }
}

class NoteTaskSuggestionRequestRejectedException(message: String) : IOException(message)
