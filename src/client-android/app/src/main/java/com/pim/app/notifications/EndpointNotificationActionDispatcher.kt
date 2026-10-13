package com.pim.app.notifications

import com.pim.core.contract.EndpointNotificationActionRequest
import com.pim.core.contract.EndpointNotificationActionResponse
import com.pim.core.contract.EndpointNotificationActionResponseApiResponse
import com.pim.core.network.ApiService

class EndpointNotificationActionDispatcher(
    private val sender: suspend (
        String,
        EndpointNotificationActionRequest
    ) -> EndpointNotificationActionResponseApiResponse
) {
    constructor(apiService: ApiService) : this(apiService::sendEndpointNotificationAction)

    suspend fun execute(
        deviceId: String,
        action: String,
        riskLevel: String,
        confirmationId: String?,
        relatedObjectType: String?,
        relatedObjectId: String?
    ): EndpointNotificationActionResponse? {
        val response = sender(
            deviceId,
            EndpointNotificationActionRequest(
                action = action,
                riskLevel = riskLevel,
                confirmationId = confirmationId,
                relatedObjectType = relatedObjectType,
                relatedObjectId = relatedObjectId
            )
        )
        return response.data
    }
}
