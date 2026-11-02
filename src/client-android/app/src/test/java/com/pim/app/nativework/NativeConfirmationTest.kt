package com.pim.app.nativework

import com.pim.core.contract.OperationConfirmationDto
import com.pim.core.contract.OperationConfirmationDtoApiResponse
import com.pim.core.contract.OperationConfirmationDtoIReadOnlyListApiResponse
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeConfirmationTest {
    @Test
    fun projectionNeverRetainsFreeFormSummaryOrRawPayload() {
        val projected = nativeOperationConfirmation(
            confirmation(
                summary = """{"graphEventId":"AAMk-private","access_token":"secret-token","mysteryKey":"private-value"}""",
                payloadJson = "payload-private-value",
                previewJson = "preview-private-value",
                operationType = "outlook.writeback",
            )
        )

        assertEquals("写入 Outlook 日历", projected.actionLabel)
        listOf(
            "AAMk-private", "secret-token", "private-value", "mysteryKey",
            "payload-private-value", "preview-private-value",
        ).forEach { privateValue ->
            assertFalse(projected.toString().contains(privateValue))
        }
        assertEquals("待确认", confirmationStatusLabel(projected.status))
        assertEquals("L4 批量或破坏性治理", confirmationRiskLabel(projected.riskLevel))
    }

    @Test
    fun unknownOperationTypeAndMalformedDatesAreNotRenderedVerbatim() {
        val projected = nativeOperationConfirmation(
            confirmation(
                operationType = "{\"access_token\":\"secret-token\",\"unknownKey\":\"private\"}",
                createdAt = "private-date-token",
                expiresAt = "AAMk-private-id",
            )
        )

        assertEquals("其他待确认操作", projected.actionLabel)
        assertEquals("未知时间", projected.createdAt)
        assertEquals("未知时间", projected.expiresAt)
        listOf("access_token", "secret-token", "unknownKey", "private", "AAMk-private-id")
            .forEach { privateValue -> assertFalse(projected.toString().contains(privateValue)) }
    }

    @Test
    fun repositoryUsesPendingDetailAndRejectContractOperations() = runBlocking {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = fakeApi { method, args ->
            calls += (method to args.dropLast(1))
            when (method) {
                "getPendingOperationConfirmations" ->
                    OperationConfirmationDtoIReadOnlyListApiResponse(
                        code = 0,
                        data = listOf(confirmation()),
                        message = "ok",
                        timestamp = "now",
                    )
                "getOperationConfirmation", "rejectOperationConfirmation" ->
                    OperationConfirmationDtoApiResponse(
                        code = 0,
                        data = confirmation(),
                        message = "ok",
                        timestamp = "now",
                    )
                else -> error("Unexpected $method")
            }
        }
        val repository = NativeWorkbenchRepository(api)

        val pending = repository.loadPendingConfirmations()
        val detail = repository.loadConfirmation("confirmation-1")
        repository.rejectConfirmation("confirmation-1")

        assertEquals(listOf("confirmation-1"), pending.map { it.id })
        assertEquals("confirmation-1", detail.id)
        assertEquals(
            listOf(
                "getPendingOperationConfirmations" to emptyList<Any?>(),
                "getOperationConfirmation" to listOf("confirmation-1"),
                "rejectOperationConfirmation" to listOf("confirmation-1"),
            ),
            calls,
        )
    }

    private fun fakeApi(handler: (String, List<Any?>) -> Any): ApiService =
        Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "toString" -> "NativeConfirmationFakeApi"
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                else -> handler(method.name, args?.toList().orEmpty())
            }
        } as ApiService

    private fun confirmation(
        summary: String = "确认测试操作",
        payloadJson: String = "payload-secret",
        previewJson: String = "preview-secret",
        operationType: String = "DeleteItem",
        createdAt: String = "2026-11-01T10:00:00Z",
        expiresAt: String = "2026-11-02T10:00:00Z",
    ) = OperationConfirmationDto(
        createdAt = createdAt,
        expiresAt = expiresAt,
        id = "confirmation-1",
        operationType = operationType,
        payloadJson = payloadJson,
        previewJson = previewJson,
        riskLevel = 14,
        source = "test",
        status = 0,
        summary = summary,
    )
}
