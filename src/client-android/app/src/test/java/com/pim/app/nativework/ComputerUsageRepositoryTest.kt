package com.pim.app.nativework

import com.pim.core.contract.PcDetailRecord
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.DeviceListDtoIReadOnlyListApiResponse
import com.pim.core.contract.SiteDailyRowDtoListApiResponse
import com.pim.core.contract.SiteSummaryDto
import com.pim.core.contract.SiteSummaryDtoApiResponse
import com.pim.core.contract.SiteTimelineRowDtoListApiResponse
import com.pim.core.contract.TypedDetailQueryResponse
import com.pim.core.contract.TypedDetailQueryResponseApiResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerUsageRepositoryTest {
    @Test
    fun sendsBoundedAllTypeDetailAndRealSummaryQueries() = runBlocking {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            val values = args?.toList().orEmpty()
            calls += method.name to values
            when (method.name) {
                "getPcDetail" -> TypedDetailQueryResponseApiResponse(
                    0,
                    TypedDetailQueryResponse(listOf(record(), windowRecord()), 2, 50, 51, 2),
                    "ok",
                    "now",
                )
                "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(0, summary(), "ok", "now")
                "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getBrowserUsageTimeline" -> SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, listOf(device()), "ok", "now")
                else -> error("Unexpected call ${method.name}")
            }
        } as ApiService

        val result = ComputerUsageRepository(api).load(" 2026-11-01 ", "2026-11-02", " device-7 ", 2)

        assertEquals(51, result.records.totalCount)
        assertEquals(setOf("web", "window"), result.records.items.map { it.recordType }.toSet())
        assertEquals("2026-11-01", calls.first { it.first == "getBrowserUsageTimeline" }.second[0])
        val detailArgs = calls.first { it.first == "getPcDetail" }.second
        assertEquals("2026-11-01", detailArgs[0])
        assertEquals("2026-11-02", detailArgs[1])
        assertEquals("device-7", detailArgs[2])
        assertEquals(null, detailArgs[3])
        assertEquals("raw", detailArgs[7])
        assertEquals(2, detailArgs[8])
        assertEquals(ComputerUsageRepository.PAGE_SIZE, detailArgs[9])
        assertEquals("2026-11-01", calls.first { it.first == "getBrowserUsageSummary" }.second[1])
        assertEquals("2026-11-02", calls.first { it.first == "getBrowserUsageSummary" }.second[2])
        assertEquals("device-7", result.devices.single().deviceId)
        assertEquals("active", calls.first { it.first == "getManagedDevices" }.second[0])
    }

    @Test
    fun rejectsEveryNonzeroEnvelopeEvenWhenDataIsPresent() {
        val operations = listOf(
            "getPcDetail",
            "getBrowserUsageSummary",
            "getBrowserUsageDaily",
            "getBrowserUsageTimeline",
            "getManagedDevices",
        )
        operations.forEach { failedOperation ->
            val api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, _ ->
                val code = if (method.name == failedOperation) 17 else 0
                when (method.name) {
                    "getPcDetail" -> TypedDetailQueryResponseApiResponse(
                        code,
                        TypedDetailQueryResponse(listOf(record()), 1, 50, 1, 1),
                        if (code == 0) "ok" else "status",
                        "now",
                    )
                    "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(code, summary(), "status", "now")
                    "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(code, emptyList(), "status", "now")
                    "getBrowserUsageTimeline" -> SiteTimelineRowDtoListApiResponse(code, emptyList(), "status", "now")
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(code, emptyList(), "status", "now")
                    else -> error("Unexpected call ${method.name}")
                }
            } as ApiService

            val error = assertThrows(NativeWorkbenchRequestException::class.java) {
                runBlocking { ComputerUsageRepository(api).load("2026-11-01", "2026-11-02", null, 1) }
            }
            assertEquals("status", error.message)
        }
    }

    @Test
    fun invalidDateRangeFailsBeforeAnyNetworkCall() {
        var calls = 0
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, _, _ ->
            calls++
            error("Invalid dates must be rejected before network")
        } as ApiService

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ComputerUsageRepository(api).load("2026-11-03", "2026-11-02", null, 1)
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun rejectsRangesOver31DaysWithoutSilentlyTruncating() {
        var calls = 0
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, _, _ ->
            calls++
            error("Over-budget date ranges must be rejected before network")
        } as ApiService

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ComputerUsageRepository(api).load("2026-09-30", "2026-10-31", null, 1)
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun accepts31InclusiveDaysAndMarksDailyRowsBeyondDisplayBudget() = runBlocking {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            calls += method.name to args?.toList().orEmpty()
            when (method.name) {
                "getPcDetail" -> TypedDetailQueryResponseApiResponse(
                    0, TypedDetailQueryResponse(emptyList(), 1, 50, 0, 0), "ok", "now",
                )
                "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(0, summary(), "ok", "now")
                "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(
                    0,
                    (0..ComputerUsageRepository.MAX_VISIBLE_DAILY_ROWS).map { index ->
                        com.pim.core.contract.SiteDailyRowDto(
                            date = "2026-10-01",
                            focusMs = index.toLong(),
                            host = "host-$index.example",
                            mediaMs = 0,
                            runMs = index.toLong(),
                            visitCount = index.toLong(),
                        )
                    },
                    "ok",
                    "now",
                )
                "getBrowserUsageTimeline" -> SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                else -> error("Unexpected call ${method.name}")
            }
        } as ApiService

        val result = ComputerUsageRepository(api).load("2026-10-01", "2026-10-31", null, 1)

        assertEquals(ComputerUsageRepository.MAX_VISIBLE_DAILY_ROWS, result.daily.size)
        assertTrue(result.dailyRowsTruncated)
        assertEquals("2026-10-01", calls.first { it.first == "getBrowserUsageDaily" }.second[1])
        assertEquals("2026-10-31", calls.first { it.first == "getBrowserUsageDaily" }.second[2])
    }

    @Test
    fun browserFilterMergesBothEventTypesBeforeGlobalPageAndCapsFetches() = runBlocking {
        val calls = mutableListOf<List<Any?>>()
        val base = Instant.parse("2026-11-02T23:59:00Z")
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getPcDetail" -> {
                    val values = args!!.toList()
                    calls += values
                    val eventType = values[3] as String
                    val apiPage = values[8] as Int
                    val pageRecords = (0 until 450).map { index ->
                        val minuteOffset = index * 2 + if (eventType == "web") 0 else 1
                        PcDetailRecord(
                            deviceId = "device-7",
                            recordType = eventType,
                            start = base.minus(minuteOffset.toLong(), ChronoUnit.MINUTES).toString(),
                            recordKey = "$eventType-$index",
                        )
                    }
                    val start = (apiPage - 1) * 200
                    val selected = pageRecords.drop(start).take(200)
                    TypedDetailQueryResponseApiResponse(
                        0,
                        TypedDetailQueryResponse(selected, apiPage, 200, 450, 3),
                        "ok",
                        "now",
                    )
                }
                "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(0, summary(), "ok", "now")
                "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getBrowserUsageTimeline" -> SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, listOf(device()), "ok", "now")
                else -> error("Unexpected call ${method.name}")
            }
        } as ApiService

        val result = ComputerUsageRepository(api).load(
            "2026-11-02", "2026-11-02", null, ComputerUsageRepository.MAX_VISIBLE_PAGES,
            ComputerUsageTypeFilter.Browser,
        )

        assertEquals(900, result.records.totalCount)
        assertEquals(ComputerUsageRepository.MAX_VISIBLE_PAGES, result.records.totalPages)
        assertEquals(50, result.records.items.size)
        assertTrue(result.records.items.all { it.recordType == "web" || it.recordType == "web-page" })
        val expected = (0 until 900).sortedByDescending { index ->
            base.minus(index.toLong(), ChronoUnit.MINUTES)
        }.drop(450).take(50)
        assertEquals(
            expected.map { index -> if (index % 2 == 0) "web-${index / 2}" else "web-page-${index / 2}" },
            result.records.items.map { it.recordKey },
        )
        assertEquals(setOf("web", "web-page"), calls.map { it[3] as String }.toSet())
        assertEquals(setOf(1, 2, 3), calls.map { it[8] as Int }.toSet())
        assertTrue(calls.all { it[9] == 200 })
        assertEquals(6, calls.size)
    }

    @Test
    fun appWindowFilterIsExactAndRejectsPageBeyondBoundBeforeNetwork() = runBlocking {
        var requestedType: Any? = null
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getPcDetail" -> {
                    requestedType = args!![3]
                    TypedDetailQueryResponseApiResponse(
                        0,
                        TypedDetailQueryResponse(listOf(windowRecord()), 1, 50, 1, 1),
                        "ok", "now",
                    )
                }
                "getBrowserUsageSummary" -> SiteSummaryDtoApiResponse(0, summary(), "ok", "now")
                "getBrowserUsageDaily" -> SiteDailyRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getBrowserUsageTimeline" -> SiteTimelineRowDtoListApiResponse(0, emptyList(), "ok", "now")
                "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                else -> error("Unexpected call ${method.name}")
            }
        } as ApiService

        ComputerUsageRepository(api).load(
            "2026-11-02", "2026-11-02", null, 1, ComputerUsageTypeFilter.AppWindow,
        )
        assertEquals("window", requestedType)

        var calls = 0
        val noNetworkApi = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, _, _ -> calls++; error("Out-of-bound page must fail before network") } as ApiService
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ComputerUsageRepository(noNetworkApi).load(
                    "2026-11-02", "2026-11-02", null,
                    ComputerUsageRepository.MAX_VISIBLE_PAGES + 1,
                )
            }
        }
        assertEquals(0, calls)
    }

    private fun record() = PcDetailRecord(
        deviceId = "device-7",
        recordType = "web",
        start = "2026-11-01T10:00:00Z",
        domain = "example.test",
        url = "https://example.test/path",
    )

    private fun windowRecord() = PcDetailRecord(
        deviceId = "device-7",
        recordType = "window",
        start = "2026-11-01T10:01:00Z",
        appName = "editor.exe",
        displayName = "Editor",
        title = "document.txt",
    )

    private fun summary() = SiteSummaryDto(
        from = "2026-11-01",
        siteCount = 0,
        to = "2026-11-02",
        topHosts = emptyList(),
        totalFocusMs = 0,
        totalMediaMs = 0,
        totalRunMs = 0,
        totalVisits = 0,
    )

    private fun device() = DeviceListDto(
        appVersion = "1",
        brand = "brand",
        dataQuality = "ok",
        deviceId = "device-7",
        displayName = "工作电脑",
        eventCount = 0,
        isOnline = true,
        lastSeenAtUtc = "now",
        locationCount = 0,
        model = "model",
        osVersion = "os",
        registeredAtUtc = "now",
        sessionCount = 0,
        storageEstimateKb = 0,
        storagePressure = "normal",
        summaryCount = 0,
        syncStatus = "synced",
    )
}
