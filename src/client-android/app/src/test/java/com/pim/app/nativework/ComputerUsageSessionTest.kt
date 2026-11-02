package com.pim.app.nativework

import com.pim.core.contract.SiteDailyRowDtoListApiResponse
import com.pim.core.contract.SiteSummaryDto
import com.pim.core.contract.SiteSummaryDtoApiResponse
import com.pim.core.contract.SiteTimelineRowDtoListApiResponse
import com.pim.core.contract.DeviceListDtoIReadOnlyListApiResponse
import com.pim.core.contract.TypedDetailQueryResponse
import com.pim.core.contract.TypedDetailQueryResponseApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerUsageSessionTest {
    @Test
    fun lateResponseFromPreviousFilterCannotPopulateCurrentScope() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val owner = "11111111-1111-4111-8111-111111111111"
            val boundApi = BoundUploadSession("https://server.example", owner)
            val current = NativeWorkbenchSession(boundApi, 1L)
            var pending: Continuation<Any?>? = null
            var timelineCalls = 0
            val detailFilters = mutableListOf<Any?>()
            lateinit var api: ApiService
            val transport = object : NativeWorkbenchTransport {
                override fun currentSession() = current
                override fun isCurrent(session: NativeWorkbenchSession) = session == current
                override fun apiFor(session: NativeWorkbenchSession): ApiService {
                    if (session != current) throw UploadSessionChangedException()
                    return api
                }
            }
            api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getPcDetail" -> {
                        detailFilters += args!![3]
                        val type = args[3] as String?
                        val item = com.pim.core.contract.PcDetailRecord(
                            deviceId = "device-1",
                            recordType = type ?: "window",
                            start = "2026-11-02T10:00:00Z",
                        )
                        TypedDetailQueryResponseApiResponse(
                            0,
                            TypedDetailQueryResponse(listOf(item), 1, 50, 1, 1),
                            "ok", "now",
                        )
                    }
                    "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(0, summary(), "ok", "now")
                    "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(0, emptyList(), "ok", "now")
                    "getBrowserUsageTimeline" -> {
                        timelineCalls++
                        if (timelineCalls == 1) {
                            pending = args!!.last() as Continuation<Any?>
                            COROUTINE_SUSPENDED
                        } else {
                            SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")
                        }
                    }
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    else -> error("Unexpected call ${method.name}")
                }
            } as ApiService
            val unusedApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, _, _ -> error("Unbound API must not be used") } as ApiService
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(unusedApi),
                legacyQuickNotes = com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository(unusedApi),
                transport = transport,
            )

            viewModel.selectSection(NativeWorkbenchSection.ComputerUsage)
            runCurrent()
            assertTrue(pending != null)

            viewModel.setComputerUsageTypeFilter(ComputerUsageTypeFilter.Browser)
            pending!!.resumeWith(Result.success(SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")))
            runCurrent()
            assertNull(viewModel.state.value.computerUsage)
            assertEquals(ComputerUsageTypeFilter.Browser, viewModel.state.value.computerUsageTypeFilter)

            viewModel.searchComputerUsage()
            runCurrent()
            assertEquals(setOf(null, "web", "web-page"), detailFilters.toSet())
            assertEquals(ComputerUsageTypeFilter.Browser, viewModel.state.value.computerUsageTypeFilter)
            assertTrue(viewModel.state.value.computerUsage != null)
            assertTrue(viewModel.state.value.computerUsage!!.records.items.all { it.recordType == "web" || it.recordType == "web-page" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun delayedComputerResponseCannotPopulateAfterOwnerSwitch() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val ownerA = "11111111-1111-4111-8111-111111111111"
            val ownerB = "22222222-2222-4222-8222-222222222222"
            val apiForA = BoundUploadSession("https://server.example", ownerA)
            val apiForB = BoundUploadSession("https://server.example", ownerB)
            var current = NativeWorkbenchSession(apiForA, 1L)
            var pending: Continuation<Any?>? = null
            lateinit var api: ApiService
            val transport = object : NativeWorkbenchTransport {
                override fun currentSession() = current
                override fun isCurrent(session: NativeWorkbenchSession) = session == current
                override fun apiFor(session: NativeWorkbenchSession): ApiService {
                    if (session != current) throw UploadSessionChangedException()
                    return api
                }
            }
            api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getPcDetail" -> TypedDetailQueryResponseApiResponse(
                        0,
                        TypedDetailQueryResponse(
                            items = listOf(
                                com.pim.core.contract.PcDetailRecord(
                                    deviceId = "device-a",
                                    recordType = "web",
                                    start = "2026-11-02T10:00:00Z",
                                    title = "owner-a record",
                                )
                            ),
                            page = 1,
                            pageSize = 50,
                            totalCount = 1,
                            totalPages = 1,
                        ),
                        "ok",
                        "now",
                    )
                    "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(0, summary(), "ok", "now")
                    "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(0, emptyList(), "ok", "now")
                    "getBrowserUsageTimeline" -> {
                        pending = args!!.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    }
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(
                        0,
                        emptyList(),
                        "ok",
                        "now",
                    )
                    else -> error("Unexpected owner-A call ${method.name}")
                }
            } as ApiService
            val unusedApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, _, _ -> error("Unbound API must not be used") } as ApiService
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(unusedApi),
                legacyQuickNotes = com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository(unusedApi),
                transport = transport,
            )

            viewModel.selectSection(NativeWorkbenchSection.ComputerUsage)
            runCurrent()
            assertTrue(pending != null)

            current = NativeWorkbenchSession(apiForB, 2L)
            pending!!.resumeWith(Result.success(SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")))
            runCurrent()

            assertNull(viewModel.state.value.computerUsage)
            assertNull(viewModel.state.value.selectedComputerRecordIndex)
            assertTrue(viewModel.state.value.error.orEmpty().contains("账号或服务器已切换"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun summary() = SiteSummaryDto(
        from = "2026-11-02",
        siteCount = 1,
        to = "2026-11-02",
        topHosts = emptyList(),
        totalFocusMs = 60_000,
        totalMediaMs = 0,
        totalRunMs = 60_000,
        totalVisits = 1,
    )
}
