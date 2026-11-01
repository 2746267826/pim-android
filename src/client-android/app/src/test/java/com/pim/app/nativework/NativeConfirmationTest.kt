package com.pim.app.nativework

import com.pim.core.contract.OperationConfirmationDto
import com.pim.core.contract.OperationConfirmationDtoApiResponse
import com.pim.core.contract.OperationConfirmationDtoIReadOnlyListApiResponse
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeConfirmationTest {
    @Test
    fun projectionOmitsRawPayloadAndRedactsPrivateSummaryValues() {
        val projected = nativeOperationConfirmation(
            confirmation(
                summary = "删除对象 graphEventId=graph-secret ChangeKey=change-secret " +
                    "token=token-secret owner@example.com " +
                    "https://example.invalid/private/route " +
                    "11111111-1111-4111-8111-111111111111",
                payloadJson = "payload-private-value",
                previewJson = "preview-private-value",
            )
        )

        assertTrue(projected.summary.startsWith("删除对象"))
        listOf(
            "graph-secret", "change-secret", "token-secret", "owner@example.com",
            "example.invalid", "11111111-1111-4111-8111-111111111111",
            "payload-private-value", "preview-private-value",
        ).forEach { privateValue ->
            assertFalse(projected.toString().contains(privateValue))
        }
        assertTrue(projected.summary.contains("[已隐藏]"))
        assertEquals("待确认", confirmationStatusLabel(projected.status))
        assertEquals("L4 批量或破坏性治理", confirmationRiskLabel(projected.riskLevel))
    }

    @Test
    fun repositoryUsesPendingDetailAndRejectContractOperations() = runBlocking {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = fakeApi { method, args ->
            calls += (method to args.dropLast(1))
            when (method) {
                "getPendingOperationConfirmations" ->
                    OperationConfirmationDtoIReadOnlyListApiResponse(
                        code = 200,
                        data = listOf(confirmation()),
                        message = "ok",
                        timestamp = "now",
                    )
                "getOperationConfirmation", "rejectOperationConfirmation" ->
                    OperationConfirmationDtoApiResponse(
                        code = 200,
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
    ) = OperationConfirmationDto(
        createdAt = "2026-11-01T10:00:00Z",
        expiresAt = "2026-11-02T10:00:00Z",
        id = "confirmation-1",
        operationType = "DeleteItem",
        payloadJson = payloadJson,
        previewJson = previewJson,
        riskLevel = 14,
        source = "test",
        status = 0,
        summary = summary,
    )
}
