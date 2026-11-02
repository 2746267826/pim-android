package com.pim.app.nativework

import com.pim.core.contract.CalendarOperationResult
import com.pim.core.contract.CalendarOperationResultApiResponse
import com.pim.core.contract.DeviceDeleteResultDto
import com.pim.core.contract.DeviceDeleteResultDtoApiResponse
import com.pim.core.contract.EventResponse
import com.pim.core.contract.StringApiResponse
import com.pim.core.contract.TaskResponse
import com.pim.core.contract.TaskResponsePagedResult
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchEnvelopeTest {
    @Test
    fun taskReadRejectsNonzeroCodeEvenWhenPayloadExists() {
        val error = assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                NativeWorkbenchRepository(
                    api(
                        TaskResponsePagedResultApiResponse(
                            code = 17,
                            data = taskPage(),
                            message = "task access denied",
                            timestamp = "now",
                        )
                    )
                ).loadTasks()
            }
        }

        assertTrue(error.message.orEmpty().contains("task access denied"))
    }

    @Test
    fun successfulCodeWithoutPayloadIsRejected() {
        val error = assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                NativeWorkbenchRepository(
                    api(
                        TaskResponsePagedResultApiResponse(
                            code = 0,
                            data = null,
                            message = "ok",
                            timestamp = "now",
                        )
                    )
                ).loadTasks()
            }
        }

        assertTrue(error.message.orEmpty().contains("服务端未返回数据"))
    }

    @Test
    fun successfulCodeAndPayloadReturnNormalResults() = runBlocking {
        val result = NativeWorkbenchRepository(
            api(
                TaskResponsePagedResultApiResponse(
                    code = 0,
                    data = taskPage(),
                    message = "ok",
                    timestamp = "now",
                )
            )
        ).loadTasks()

        assertEquals(0, result.totalCount)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun taskDeleteRequiresItsStructuredOperationResult() = runBlocking {
        val result = CalendarOperationResult(
            affectedCount = 1,
            affectedIds = listOf("task-1"),
            message = "deleted",
            operation = "delete",
            operationId = "operation-1",
            samples = emptyList(),
        )
        val task = task()

        NativeWorkbenchRepository(
            api(
                CalendarOperationResultApiResponse(
                    code = 0,
                    data = result,
                    message = "ok",
                    timestamp = "now",
                )
            )
        ).deleteTask(task)

        val error = assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                NativeWorkbenchRepository(
                    api(
                        CalendarOperationResultApiResponse(
                            code = 9,
                            data = result,
                            message = "delete denied",
                            timestamp = "now",
                        )
                    )
                ).deleteTask(task)
            }
        }
        assertTrue(error.message.orEmpty().contains("delete denied"))
    }

    @Test
    fun deviceDeleteUsesTypedResultAndStringDeleteRequiresNonblankAck() = runBlocking {
        val deviceResult = DeviceDeleteResultDto(
            deviceId = "device-1",
            tables = emptyList(),
            totalRows = 0,
        )
        val deleted = NativeWorkbenchRepository(
            api(
                DeviceDeleteResultDtoApiResponse(
                    code = 0,
                    data = deviceResult,
                    message = "ok",
                    timestamp = "now",
                )
            )
        ).deleteDevice("device-1")
        assertEquals("device-1", deleted.deviceId)

        val event = event()
        val blankAck = assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                NativeWorkbenchRepository(
                    api(StringApiResponse(code = 0, data = "  ", message = "ok", timestamp = "now"))
                ).deleteEvent(event)
            }
        }
        assertTrue(blankAck.message.orEmpty().contains("未返回确认信息"))

        NativeWorkbenchRepository(
            api(StringApiResponse(code = 0, data = "deleted", message = "ok", timestamp = "now"))
        ).deleteEvent(event)

        val failedAck = assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                NativeWorkbenchRepository(
                    api(
                        StringApiResponse(
                            code = 5,
                            data = "deleted",
                            message = "delete denied",
                            timestamp = "now",
                        )
                    )
                ).deleteEvent(event)
            }
        }
        assertTrue(failedAck.message.orEmpty().contains("delete denied"))
    }

    private fun api(response: Any): ApiService =
        Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "NativeWorkbenchEnvelopeFakeApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> response
            }
        } as ApiService

    private fun taskPage() = TaskResponsePagedResult(
        items = emptyList(),
        page = 1,
        pageSize = 100,
        totalCount = 0,
        totalPages = 0,
    )

    private fun task() = TaskResponse(
        calendarId = "calendar-1",
        id = "task-1",
        isInbox = false,
        priority = 3,
        sortOrder = 0,
        status = "NEEDS-ACTION",
        subTasks = emptyList(),
        title = "Task",
        uid = "task-uid",
    )

    private fun event() = EventResponse(
        calendarId = "calendar-1",
        dtEnd = "2026-11-02T10:00:00Z",
        dtStart = "2026-11-02T09:00:00Z",
        id = "event-1",
        rRule = null,
        source = "local",
        status = "CONFIRMED",
        title = "Event",
        uid = "event-uid",
    )
}
