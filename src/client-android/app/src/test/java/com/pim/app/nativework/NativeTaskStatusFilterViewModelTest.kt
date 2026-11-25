package com.pim.app.nativework

import com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.EventResponsePagedResult
import com.pim.core.contract.EventResponsePagedResultApiResponse
import com.pim.core.contract.MobileUsageSummaryResponse
import com.pim.core.contract.MobileUsageSummaryResponseApiResponse
import com.pim.core.contract.TaskBookDtoIReadOnlyListApiResponse
import com.pim.core.contract.TaskResponsePagedResult
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeTaskStatusFilterViewModelTest {
    @Test
    fun selectedStatusesAreSentAsOrSetAndClearRestoresUnfilteredState() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val statusFilters = mutableListOf<List<String>?>()
            val api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getTasksPaged" -> {
                        @Suppress("UNCHECKED_CAST")
                        statusFilters += args[3] as? List<String>
                        TaskResponsePagedResultApiResponse(
                            0,
                            TaskResponsePagedResult(emptyList(), 1, 100, 0, 0),
                            "ok",
                            "now",
                        )
                    }
                    "getTaskBooks" -> TaskBookDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getEvents" -> EventResponsePagedResultApiResponse(
                        0,
                        EventResponsePagedResult(emptyList(), 1, 100, 0, 0),
                        "ok",
                        "now",
                    )
                    "getCalendars" -> CalendarResponseListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(
                        0,
                        MobileUsageSummaryResponse(
                            appRanking = emptyList(),
                            appSwitchCount = 0,
                            appsUsed = 0,
                            completeness = 1.0,
                            date = "2026-11-25",
                            fallbackForegroundSeconds = 0,
                            generatedAt = "now",
                            qualityIssueCount = 0,
                            syncBatches = emptyList(),
                            totalForegroundSeconds = 0,
                        ),
                        "ok",
                        "now",
                    )
                    else -> error("Unexpected API call ${method.name}")
                }
            } as ApiService
            val transport = object : NativeWorkbenchTransport {
                private val session = NativeWorkbenchSession(
                    BoundUploadSession(
                        "https://pim.example",
                        "11111111-1111-4111-8111-111111111111",
                    ),
                    1L,
                )

                override fun currentSession() = session
                override fun isCurrent(session: NativeWorkbenchSession) = this.session == session
                override fun apiFor(session: NativeWorkbenchSession) = api
            }
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(api),
                legacyQuickNotes = LegacyQuickNoteRepository(api),
                transport = transport,
            )

            runCurrent()
            viewModel.selectSection(NativeWorkbenchSection.Tasks)
            runCurrent()
            statusFilters.clear()

            viewModel.toggleTaskStatus(NativeTaskStatusFilter.IN_PROCESS)
            runCurrent()
            assertEquals(listOf(listOf("IN-PROCESS")), statusFilters)

            viewModel.toggleTaskStatus(NativeTaskStatusFilter.COMPLETED)
            runCurrent()
            assertEquals(listOf("IN-PROCESS", "COMPLETED"), statusFilters.last())

            viewModel.toggleTaskStatus(NativeTaskStatusFilter.IN_PROCESS)
            runCurrent()
            assertEquals(listOf("COMPLETED"), statusFilters.last())

            viewModel.clearTaskStatuses()
            runCurrent()
            assertNull(statusFilters.last())
            assertEquals(emptyList<String>(), viewModel.state.value.taskStatuses)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
