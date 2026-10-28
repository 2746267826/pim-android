package com.pim.app.testing

import com.pim.core.contract.*
import com.pim.core.network.ApiService
import okhttp3.RequestBody

/** Legacy endpoint fixtures must fail if they unexpectedly start any native-note network work. */
internal interface UnusedNoteOperations : ApiService {
    override suspend fun beginNoteMaterialUpload(request: BeginNoteMaterialUploadRequest): NoteMaterialUploadDtoApiResponse = error("Unexpected note upload")
    override suspend fun getNoteMaterialVersion(versionId: String): NoteMaterialVersionDtoApiResponse = error("Unexpected note read")
    override suspend fun uploadNoteMaterialPart(versionId: String, fileIndex: Int, partIndex: Int, sha256: String, body: RequestBody): StringApiResponse = error("Unexpected note bytes")
    override suspend fun completeNoteMaterialUpload(versionId: String): NoteMaterialUploadDtoApiResponse = error("Unexpected note completion")
}
