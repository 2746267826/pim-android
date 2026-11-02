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
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
