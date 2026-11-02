package com.pim.app.ui.tracks

import com.pim.app.nativework.NativeWorkbenchSession
import com.pim.app.nativework.NativeWorkbenchTransport
import com.pim.core.contract.DeviceListDtoIReadOnlyListApiResponse
import com.pim.core.contract.MobileLocationPathPointDto
import com.pim.core.contract.MobileLocationPointDto
import com.pim.core.contract.MobileLocationSegmentDto
import com.pim.core.contract.MobileLocationSegmentPointPageDto
import com.pim.core.contract.MobileLocationSegmentPointPageDtoApiResponse
import com.pim.core.contract.MobileLocationTrackDto
import com.pim.core.contract.MobileLocationTrackDtoIReadOnlyListApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationHistoryViewModelTest {
    private val ownerA = "11111111-1111-4111-8111-111111111111"
    private val ownerB = "22222222-2222-4222-8222-222222222222"

    @Test
    fun queryUsesLocalDateBoundaryAndRejectsRangeLongerThanSevenDays() {
        val query = locationHistoryQuery(
            startDate = "2026-11-01",
            endDate = "2026-11-07",
            deviceId = "device-a",
            zoneId = ZoneId.of("America/Los_Angeles"),
        )

        assertEquals("2026-11-01T07:00:00Z", query.startUtc)
        assertEquals("2026-11-08T08:00:00Z", query.endUtc)
        assertEquals("America/Los_Angeles", query.timeZone)
        assertEquals("device-a", query.deviceId)
        assertTrue(runCatching {
            locationHistoryQuery("2026-11-01", "2026-11-08", null, ZoneId.of("UTC"))
        }.exceptionOrNull()?.message.orEmpty().contains("最多覆盖 7 天"))
    }

    @Test
    fun delayedTracksResponseAfterOwnerChangeClearsOldFilterAndData() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            var pendingTracks: Continuation<Any?>? = null
            val api = apiProxy { method, args ->
                when (method.name) {
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileLocationTracks" -> {
                        pendingTracks = args.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    }
                    else -> error("Unexpected API call ${method.name}")
                }
            }
            val transport = FakeTransport(session(ownerA, 1), api)
            val viewModel = LocationHistoryViewModel(transport)
            viewModel.setDevice("owner-a-device")
            viewModel.refresh()
            runCurrent()

            assertTrue(pendingTracks != null)
            transport.current = session(ownerB, 2)
            pendingTracks!!.resumeWith(Result.success(
                MobileLocationTrackDtoIReadOnlyListApiResponse(0, listOf(track("owner-a-track")), "ok", "now")
            ))
            runCurrent()

            assertTrue(viewModel.state.value.tracks.isEmpty())
            assertEquals(0, viewModel.state.value.totalTrackCount)
            assertNull(viewModel.state.value.selectedDeviceId)
            assertTrue(viewModel.state.value.devices.isEmpty())
            assertFalse(viewModel.state.value.loading)
            assertTrue(viewModel.state.value.message.orEmpty().contains("旧位置数据已清除"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun repositoryRejectsErrorCodeAndMissingDataInsteadOfReturningEmptyTracks() = runTest {
        val query = locationHistoryQuery(
            "2026-11-01",
            "2026-11-01",
            null,
            ZoneId.of("UTC"),
        )

        val rejected = LocationHistoryRepository(
            FakeTransport(
                session(ownerA, 1),
                apiProxy { method, _ ->
                    when (method.name) {
                        "getMobileLocationTracks" -> MobileLocationTrackDtoIReadOnlyListApiResponse(
                            403,
                            emptyList(),
                            "forbidden",
                            "now",
                        )
                        else -> error("Unexpected API call ${method.name}")
                    }
                },
            ),
        )
        val codeError = runCatching { rejected.tracks(session(ownerA, 1), query) }.exceptionOrNull()
        assertTrue(codeError is IllegalStateException)
        assertTrue(codeError?.message.orEmpty().contains("forbidden"))

        val missing = LocationHistoryRepository(
            FakeTransport(
                session(ownerA, 1),
                apiProxy { method, _ ->
                    when (method.name) {
                        "getMobileLocationTracks" -> MobileLocationTrackDtoIReadOnlyListApiResponse(
                            0,
                            null,
                            "ok",
                            "now",
                        )
                        else -> error("Unexpected API call ${method.name}")
                    }
                },
            ),
        )
        val missingError = runCatching { missing.tracks(session(ownerA, 1), query) }.exceptionOrNull()
        assertTrue(missingError is IllegalStateException)
        assertTrue(missingError?.message.orEmpty().contains("未返回数据"))
    }

    @Test
    fun trackDisplayIsCappedAndPointPagesUseCursorAndBoundedPageSize() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val pointCalls = mutableListOf<Pair<String?, Int?>>()
            val tracks = (1..55).map { track("track-$it") }
            val api = apiProxy { method, args ->
                when (method.name) {
                    "getManagedDevices" -> DeviceListDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileLocationTracks" -> MobileLocationTrackDtoIReadOnlyListApiResponse(0, tracks, "ok", "now")
                    "getMobileLocationSegmentPoints" -> {
                        pointCalls += (args[7] as String?) to (args[8] as Int?)
                        val index = pointCalls.size
                        MobileLocationSegmentPointPageDtoApiResponse(
                            0,
                            MobileLocationSegmentPointPageDto(
                                hasMore = index == 1,
                                items = listOf(point("point-$index")),
                                nextCursor = if (index == 1) "cursor-2" else null,
                            ),
                            "ok",
                            "now",
                        )
                    }
                    else -> error("Unexpected API call ${method.name}")
                }
            }
            val viewModel = LocationHistoryViewModel(FakeTransport(session(ownerA, 1), api))
            viewModel.refresh()
            runCurrent()

            assertEquals(55, viewModel.state.value.totalTrackCount)
            assertEquals(LOCATION_HISTORY_MAX_RENDERED_TRACKS, viewModel.state.value.tracks.size)
            val selectedTrack = viewModel.state.value.tracks.first()
            viewModel.openSegment(selectedTrack, selectedTrack.segments.first())
            runCurrent()
            assertEquals(LOCATION_HISTORY_POINTS_PAGE_SIZE, pointCalls[0].second)
            assertEquals("point-1", viewModel.state.value.detailPoints.single().id)
            assertTrue(viewModel.state.value.detailHasMore)

            viewModel.loadNextPointsPage()
            runCurrent()
            assertEquals("cursor-2", pointCalls[1].first)
            assertEquals(LOCATION_HISTORY_POINTS_PAGE_SIZE, pointCalls[1].second)
            assertEquals(listOf("point-1", "point-2"), viewModel.state.value.detailPoints.map { it.id })
            assertFalse(viewModel.state.value.detailHasMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun apiProxy(block: (java.lang.reflect.Method, Array<out Any?>) -> Any?): ApiService =
        Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            block(method, args ?: emptyArray())
        } as ApiService

    private fun session(owner: String, generation: Long) = NativeWorkbenchSession(
        BoundUploadSession("https://pim.example", owner),
        generation,
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

    private fun track(id: String) = MobileLocationTrackDto(
        deviceId = "device-a",
        distanceMeters = 1200.0,
        durationSeconds = 600,
        endUtc = "2026-11-01T11:00:00Z",
        id = id,
        pointCount = 2,
        qualityFlags = emptyList(),
        segmentCount = 1,
        segments = listOf(segment(id)),
        startUtc = "2026-11-01T10:00:00Z",
    )

    private fun segment(trackId: String) = MobileLocationSegmentDto(
        averageAccuracyMeters = 5.0,
        averageSpeedMetersPerSecond = 2.0,
        deviceId = "device-a",
        distanceMeters = 1200.0,
        durationSeconds = 600,
        endUtc = "2026-11-01T11:00:00Z",
        id = "segment-$trackId",
        kind = "walking",
        localEnd = "2026-11-01 11:00",
        localStart = "2026-11-01 10:00",
        maxAccuracyMeters = 8.0,
        path = listOf(
            MobileLocationPathPointDto(5.0, "path-a", 37.0, -122.0, "good", emptyList(), "2026-11-01T10:00:00Z"),
            MobileLocationPathPointDto(5.0, "path-b", 37.1, -122.1, "good", emptyList(), "2026-11-01T11:00:00Z"),
        ),
        pointCount = 2,
        quality = "good",
        qualityFlags = emptyList(),
        startUtc = "2026-11-01T10:00:00Z",
        trackId = trackId,
    )

    private fun point(id: String) = MobileLocationPointDto(
        deviceId = "device-a",
        horizontalAccuracyMeters = 5.0,
        id = id,
        isAutoSubmitted = true,
        latitude = 37.0,
        longitude = -122.0,
        provider = "fused",
        quality = "good",
        rawJson = "{}",
        recordedAtUtc = "2026-11-01T10:30:00Z",
        sourceKind = "foreground",
        submittedAtUtc = "2026-11-01T10:30:01Z",
    )
}
