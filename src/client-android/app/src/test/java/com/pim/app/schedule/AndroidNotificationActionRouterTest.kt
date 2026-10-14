package com.pim.app.schedule

import com.pim.app.notifications.NotificationRoute
import com.pim.app.notifications.EndpointNotificationActionDispatcher
import com.pim.app.notifications.PimNotificationRouter
import com.pim.core.contract.EndpointNotificationActionRequest
import com.pim.core.contract.EndpointNotificationActionResponse
import com.pim.core.contract.EndpointNotificationActionResponseApiResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNotificationActionRouterTest {
    @Test
    fun lowRiskActionCanExecuteDirectly() {
        val result = PimNotificationRouter().route("dismiss", "L1LowRiskAction")

        assertEquals(NotificationRoute.ExecuteOnline, result)
    }

    @Test
    fun highRiskActionOpensDetail() {
        val result = PimNotificationRouter().route(
            action = "confirm",
            riskLevel = "L3ExternalSourceOrWriteback",
            confirmationId = "confirmation-1"
        )

        assertTrue(result is NotificationRoute.OpenDetail)
        assertEquals("/confirmations/confirmation-1", (result as NotificationRoute.OpenDetail).detailUrl)
    }

    @Test
    fun lowRiskDispatcherCallsEndpointNotificationActionApi() = runBlocking {
        var capturedDeviceId = ""
        var capturedRequest: EndpointNotificationActionRequest? = null
        val dispatcher = EndpointNotificationActionDispatcher { deviceId, request ->
            capturedDeviceId = deviceId
            capturedRequest = request
            EndpointNotificationActionResponseApiResponse(
                code = 0,
                message = "OK",
                data = EndpointNotificationActionResponse(
                    result = "Executed",
                    detailUrl = null,
                    message = "recorded"
                ),
                timestamp = "2026-10-14T00:00:00Z",
            )
        }

        val response = dispatcher.execute(
            deviceId = "android-1",
            action = "dismiss",
            riskLevel = "L1LowRiskAction",
            confirmationId = null,
            relatedObjectType = "task",
            relatedObjectId = "task-1"
        )

        assertEquals("android-1", capturedDeviceId)
        assertEquals("dismiss", capturedRequest?.action)
        assertEquals("L1LowRiskAction", capturedRequest?.riskLevel)
        assertEquals("task", capturedRequest?.relatedObjectType)
        assertEquals("Executed", response?.result)
    }
}
