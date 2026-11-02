package com.pim.app.ui.tracks

import com.pim.app.nativework.NativeWorkbenchSession
import com.pim.app.nativework.NativeWorkbenchTransport
import com.pim.core.contract.DeviceListDtoIReadOnlyListApiResponse
import com.pim.core.contract.MobileAppUsageSummaryDto
import com.pim.core.contract.MobileTimelineItemDto
import com.pim.core.contract.MobileTimelineResponse
import com.pim.core.contract.MobileTimelineResponseApiResponse
import com.pim.core.contract.MobileUsageSummaryResponse
import com.pim.core.contract.MobileUsageSummaryResponseApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUsageHistoryTest {
    private val ownerA = "11111111-1111-4111-8111-111111111111"
    private val ownerB = "22222222-2222-4222-8222-222222222222"

    @Test
    fun dateRangeIsBoundedAndBuildsInclusiveDays() {
        val query = mobileUsageHistoryQuery("2026-11-01", "2026-11-03", "device-a")
        assertEquals(
            listOf(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03")),
            query.dates,
        )
        assertEquals("device-a", query.deviceId)
        assertTrue(runCatching {
            mobileUsageHistoryQuery("2026-11-01", "2026-11-08", null)
        }.exceptionOrNull()?.message.orEmpty().contains("最多覆盖 7 天"))
    }

    @Test
    fun loadDayUsesContractedSummaryAndTimelinePaginationWithStatusValidation() = runTest {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val transport = FakeTransport(session(ownerA, 1), apiProxy { method, args ->
            calls += method.name to args.toList()
            when (method.name) {
                "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(0, summary("2026-11-01"), "ok", "now")
                "getMobileTimeline" -> MobileTimelineResponseApiResponse(0, timeline("2026-11-01", 1), "ok", "now")
                else -> error("Unexpected API call ${method.name}")
            }
        })
        val day = MobileUsageHistoryRepository(transport).loadDay(
            session(ownerA, 1), LocalDate.parse("2026-11-01"), "device-a",
        )
        val summaryArgs = calls.first { it.first == "getMobileSummary" }.second
        assertEquals("2026-11-01", summaryArgs[0])
        assertEquals("device-a", summaryArgs[1])
        val timelineArgs = calls.first { it.first == "getMobileTimeline" }.second
        assertEquals("2026-11-01", timelineArgs[0])
        assertEquals("device-a", timelineArgs[1])
        assertEquals(1, timelineArgs[2])
        assertEquals(MOBILE_USAGE_HISTORY_PAGE_SIZE, timelineArgs[3])
        assertEquals(listOf("synthetic-1", "synthetic-2"), day.timeline.items.map { it.id })
        assertTrue(day.timeline.hasMore == true)

        val rejected = MobileUsageHistoryRepository(FakeTransport(session(ownerA, 1), apiProxy { method, _ ->
            when (method.name) {
                "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(403, summary("2026-11-01"), "denied", "now")
                else -> error("Summary rejection should stop before timeline")
            }
        }))
        val rejectedError = runCatching {
            rejected.loadDay(session(ownerA, 1), LocalDate.parse("2026-11-01"), null)
        }.exceptionOrNull()
        assertTrue(rejectedError is IllegalStateException)
        assertTrue(rejectedError?.message.orEmpty().contains("403"))

        val wrongDate = MobileUsageHistoryRepository(FakeTransport(session(ownerA, 1), apiProxy { method, _ ->
            when (method.name) {
                "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(0, summary("2026-11-02"), "ok", "now")
                else -> error("Date mismatch should stop before timeline")
            }
        }))
        assertTrue(runCatching {
            wrongDate.loadDay(session(ownerA, 1), LocalDate.parse("2026-11-01"), null)
        }.exceptionOrNull()?.message.orEmpty().contains("日期与请求不一致"))
    }

    @Test
    fun timelineRejectsNonzeroCodeMissingDataWrongDateAndWrongPage() = runTest {
        val date = LocalDate.parse("2026-11-01")
        val cases = listOf(
            MobileTimelineResponseApiResponse(403, timeline("2026-11-01", 1), "denied", "now") to "403",
            MobileTimelineResponseApiResponse(0, null, "ok", "now") to "未返回数据",
            MobileTimelineResponseApiResponse(0, timeline("2026-11-02", 1), "ok", "now") to "日期与请求不一致",
            MobileTimelineResponseApiResponse(0, timeline("2026-11-01", 2), "ok", "now") to "页码与请求不一致",
        )
        for ((response, message) in cases) {
            val repository = MobileUsageHistoryRepository(FakeTransport(session(ownerA, 1), apiProxy { method, _ ->
                when (method.name) {
                    "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(0, summary("2026-11-01"), "ok", "now")
                    "getMobileTimeline" -> response
                    else -> error("Unexpected API call ${method.name}")
                }
            }))
            val error = runCatching { repository.loadDay(session(ownerA, 1), date, null) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue("$message: ${error?.message}", error?.message.orEmpty().contains(message))
        }
    }

    @Test
    fun pagesAreBoundedBeforeAnyNetworkCall() = runTest {
        var calls = 0
        val repository = MobileUsageHistoryRepository(FakeTransport(session(ownerA, 1), apiProxy { _, _ ->
            calls++
            error("Invalid page must not call API")
        }))
        val error = runCatching {
            repository.loadDay(
                session(ownerA, 1), LocalDate.parse("2026-11-01"), null,
                page = MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY + 1,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, calls)
    }

    @Test
    fun delayedTimelineResponseAfterSessionChangeDoesNotPopulateState() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            var pendingTimeline: Continuation<Any?>? = null
            val api = apiProxy { method, args ->
                when (method.name) {
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(0, summary("2026-11-01"), "ok", "now")
                    "getMobileTimeline" -> {
                        pendingTimeline = args.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    }
                    else -> error("Unexpected API call ${method.name}")
                }
            }
            val transport = FakeTransport(session(ownerA, 1), api)
            val viewModel = MobileUsageHistoryViewModel(transport)
            viewModel.setStartDate("2026-11-01")
            viewModel.setEndDate("2026-11-01")
            viewModel.refresh()
            runCurrent()
            assertTrue(pendingTimeline != null)

            transport.current = session(ownerB, 2)
            pendingTimeline!!.resumeWith(Result.success(MobileTimelineResponseApiResponse(0, timeline("2026-11-01", 1), "ok", "now")))
            runCurrent()

            assertTrue(viewModel.state.value.days.isEmpty())
            assertTrue(viewModel.state.value.devices.isEmpty())
            assertFalse(viewModel.state.value.loading)
            assertTrue(viewModel.state.value.message.orEmpty().contains("旧手机使用数据已清除"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun viewModelAppendsNextTimelinePageAndStopsWhenServerHasNoMore() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val requestedPages = mutableListOf<Int>()
            val api = apiProxy { method, args ->
                when (method.name) {
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(0, summary("2026-11-01"), "ok", "now")
                    "getMobileTimeline" -> {
                        val page = args[2] as Int
                        requestedPages += page
                        MobileTimelineResponseApiResponse(0, timeline("2026-11-01", page), "ok", "now")
                    }
                    else -> error("Unexpected API call ${method.name}")
                }
            }
            val viewModel = MobileUsageHistoryViewModel(FakeTransport(session(ownerA, 1), api))
            viewModel.setStartDate("2026-11-01")
            viewModel.setEndDate("2026-11-01")
            viewModel.refresh()
            runCurrent()

            assertEquals(listOf(1), requestedPages)
            viewModel.loadMore(LocalDate.parse("2026-11-01"))
            runCurrent()

            val loaded = viewModel.state.value.days.single().timeline
            assertEquals(listOf(1, 2), requestedPages)
            assertEquals(listOf("synthetic-1", "synthetic-2", "synthetic-3"), loaded.items.map { it.id })
            assertEquals(2, loaded.page)
            assertEquals(3, loaded.totalCount)
            assertEquals(false, loaded.hasMore)

            viewModel.loadMore(LocalDate.parse("2026-11-01"))
            runCurrent()
            assertEquals(listOf(1, 2), requestedPages)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun invalidOrOtherDayPaginationDoesNotCancelActivePage() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val firstDate = "2026-11-01"
            val secondDate = "2026-11-02"
            val finalDate = "2026-11-03"
            val requestedPages = mutableListOf<Pair<String, Int>>()
            var pendingFirstDatePage: Continuation<Any?>? = null
            val api = apiProxy { method, args ->
                when (method.name) {
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileSummary" -> {
                        val date = args[0] as String
                        MobileUsageSummaryResponseApiResponse(0, summary(date), "ok", "now")
                    }
                    "getMobileTimeline" -> {
                        val date = args[0] as String
                        val page = args[2] as Int
                        requestedPages += date to page
                        if (date == firstDate && page == 2) {
                            pendingFirstDatePage = args.last() as Continuation<Any?>
                            COROUTINE_SUSPENDED
                        } else {
                            val hasMore = date != finalDate && page == 1
                            MobileTimelineResponseApiResponse(
                                0,
                                timeline(date, page, hasMore),
                                "ok",
                                "now",
                            )
                        }
                    }
                    else -> error("Unexpected API call ${method.name}")
                }
            }
            val viewModel = MobileUsageHistoryViewModel(FakeTransport(session(ownerA, 1), api))
            viewModel.setStartDate(firstDate)
            viewModel.setEndDate(finalDate)
            viewModel.refresh()
            runCurrent()

            assertEquals(listOf(firstDate to 1, secondDate to 1, finalDate to 1), requestedPages)
            val firstDay = LocalDate.parse(firstDate)
            viewModel.loadMore(firstDay)
            runCurrent()
            assertTrue(pendingFirstDatePage != null)
            assertTrue(viewModel.state.value.days.first { it.date == firstDay }.loadingMore)

            viewModel.loadMore(LocalDate.parse(secondDate))
            viewModel.loadMore(LocalDate.parse(finalDate))
            viewModel.loadMore(LocalDate.parse("2026-11-04"))
            runCurrent()

            assertEquals(
                listOf(firstDate to 1, secondDate to 1, finalDate to 1, firstDate to 2),
                requestedPages,
            )
            assertTrue(viewModel.state.value.days.first { it.date == firstDay }.loadingMore)
            assertFalse(viewModel.state.value.days.first { it.date == LocalDate.parse(secondDate) }.loadingMore)
            assertFalse(viewModel.state.value.days.first { it.date == LocalDate.parse(finalDate) }.loadingMore)

            pendingFirstDatePage!!.resumeWith(
                Result.success(
                    MobileTimelineResponseApiResponse(0, timeline(firstDate, 2, false), "ok", "now"),
                ),
            )
            runCurrent()

            val firstDayState = viewModel.state.value.days.first { it.date == firstDay }
            assertFalse(firstDayState.loadingMore)
            assertEquals(2, firstDayState.timeline.page)
            assertEquals(3, firstDayState.timeline.items.size)
            assertFalse(viewModel.state.value.days.any { it.loadingMore })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun apiProxy(block: (java.lang.reflect.Method, Array<out Any?>) -> Any?): ApiService =
        Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            block(method, args ?: emptyArray())
        } as ApiService

    private fun session(owner: String, generation: Long) = NativeWorkbenchSession(
        BoundUploadSession("https://pim.example", owner), generation,
    )

    private class FakeTransport(
        var current: NativeWorkbenchSession?,
        private val api: ApiService,
    ) : NativeWorkbenchTransport {
        override fun currentSession(): NativeWorkbenchSession? = current
        override fun isCurrent(session: NativeWorkbenchSession): Boolean = current == session
        override fun apiFor(session: NativeWorkbenchSession): ApiService {
            check(isCurrent(session))
            return api
        }
    }

    private fun summary(date: String) = MobileUsageSummaryResponse(
        appRanking = listOf(
            MobileAppUsageSummaryDto(
                categoryName = null,
                displayName = "Synthetic app",
                foregroundSeconds = 60,
                lastUsedAt = null,
                launchCount = 1,
                packageName = "synthetic.app",
                sessionCount = 1,
                share = 1.0,
                source = "synthetic",
            ),
        ),
        appSwitchCount = 0,
        appsUsed = 1,
        completeness = 1.0,
        date = date,
        deviceId = "device-a",
        fallbackForegroundSeconds = 0,
        generatedAt = "2026-11-02T00:00:00Z",
        qualityIssueCount = 0,
        syncBatches = emptyList(),
        totalForegroundSeconds = 60,
    )

    private fun timeline(date: String, page: Int, hasMore: Boolean = page == 1) = MobileTimelineResponse(
        date = date,
        deviceId = "device-a",
        fallbackSummaries = if (page == 1) listOf(item("synthetic-2", "fallback")) else emptyList(),
        fallbackTotalCount = 1,
        generatedAt = "2026-11-02T00:00:00Z",
        hasMore = hasMore,
        items = when (page) {
            1 -> listOf(item("synthetic-1", "session"), item("synthetic-2", "fallback"))
            2 -> listOf(item("synthetic-3", "session"))
            else -> emptyList()
        },
        page = page,
        pageSize = MOBILE_USAGE_HISTORY_PAGE_SIZE,
        sessionTotalCount = if (page == 1) 1 else 2,
        sessions = when (page) {
            1 -> listOf(item("synthetic-1", "session"))
            2 -> listOf(item("synthetic-3", "session"))
            else -> emptyList()
        },
        totalCount = 3,
        truncated = hasMore,
    )

    private fun item(id: String, kind: String) = MobileTimelineItemDto(
        confidence = 1.0,
        deviceId = "device-a",
        displayName = "Synthetic app",
        durationSeconds = 60,
        end = "2026-11-01T10:01:00Z",
        id = id,
        kind = kind,
        packageName = "synthetic.app",
        reason = "synthetic",
        source = if (kind == "fallback") "fallback" else "events",
        start = "2026-11-01T10:00:00Z",
    )
}
