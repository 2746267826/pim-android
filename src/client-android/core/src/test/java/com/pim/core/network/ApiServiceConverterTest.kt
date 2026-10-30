package com.pim.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pim.core.contract.LoginRequest
import com.pim.core.contract.MobileUsageEventsUploadRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

class ApiServiceConverterTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginRequestSerializerWritesJson() {
        val json = Json.encodeToString(LoginRequest(username = "alice", password = "secret"))

        assertTrue(json, json.contains("\"username\":\"alice\""))
        assertTrue(json, json.contains("\"password\":\"secret\""))
    }

    @Test
    fun loginRequestBodyConverterWritesJson() {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://127.0.0.1/")
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()

        val converter = retrofit.requestBodyConverter<LoginRequest>(
            LoginRequest::class.java,
            emptyArray<Annotation>(),
            emptyArray<Annotation>()
        )
        val body = converter.convert(LoginRequest(username = "alice", password = "secret")) ?: error("Converter returned null")
        val buffer = Buffer()

        body.writeTo(buffer)
        val json = buffer.readUtf8()

        assertTrue(json, json.contains("\"username\":\"alice\""))
        assertTrue(json, json.contains("\"password\":\"secret\""))
    }

    @Test
    fun `real Retrofit parses a complete HTTP 202 envelope with unknown future fields`() = runBlocking {
        val body = """
            {
              "code": 0,
              "message": "Batch is already being processed.",
              "data": {
                "batchId": "fixture-batch",
                "acceptedCount": 0,
                "skippedCount": 0,
                "rejectedCount": 0,
                "failedCount": 3,
                "itemResults": [],
                "processingState": "in-flight",
                "retryAfterSeconds": 15,
                "leaseUntilUtc": "2026-10-30T04:15:00Z",
                "retryAtUtc": null
              },
              "timestamp": "2026-10-30T04:00:20Z"
            }
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "15")
                .setBody(body)
        )
        val uploadApi = uploadApi(server.url("/api/v1/").toString())
        val response = uploadApi.uploadMobileUsage(usageUploadRequest())

        assertEquals(0, response.code)
        assertEquals("fixture-batch", response.data?.batchId)
        assertEquals(3, response.data?.failedCount)
        assertEquals(emptyList<Any>(), response.data?.itemResults)
        assertEquals("/api/v1/mobile/usage/events", server.takeRequest().path)
    }

    @Test
    fun `real Retrofit exposes a complete HTTP 202 null data envelope for coordinator retry`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"code":0,"data":null,"message":"accepted without data","timestamp":"2026-10-30T04:00:20Z"}"""
                )
        )
        val response = uploadApi(server.url("/api/v1/").toString())
            .uploadMobileUsage(usageUploadRequest())

        assertEquals(0, response.code)
        assertNull(response.data)
    }

    @Test
    fun `real Retrofit empty HTTP 202 body is an observable failure not a parsed null data envelope`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("")
        )
        val api = uploadApi(server.url("/api/v1/").toString())
        val failure = try {
            api.uploadMobileUsage(usageUploadRequest())
            null
        } catch (throwable: Throwable) {
            throwable
        }

        assertEquals(
            "kotlinx.serialization.json.internal.JsonDecodingException",
            failure?.javaClass?.name
        )
        assertTrue(
            "empty-body failure must retain an observable message: $failure",
            failure?.message.orEmpty().isNotBlank()
        )
        assertTrue(
            "empty body must not be reported as a normal data=null response",
            failure?.message?.contains("accepted without data") != true
        )
    }

    private fun uploadApi(baseUrl: String): ApiService {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    private fun usageUploadRequest(): MobileUsageEventsUploadRequest =
        MobileUsageEventsUploadRequest(
            apps = emptyList(),
            clientBatchId = "fixture-client-batch",
            deviceId = "fixture-device",
            events = emptyList(),
            fallbackSummaries = emptyList(),
            sourceWindowEndUtc = "2026-10-30T04:00:20Z",
            sourceWindowStartUtc = "2026-10-30T04:00:00Z"
        )
}
