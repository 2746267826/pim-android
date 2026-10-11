package com.pim.app.ui.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.core.settings.ServerSettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeEmbedIndependentControlsTest {
    private lateinit var server: MockWebServer
    private lateinit var session: RecordingAuthSessionStore
    private lateinit var settings: ServerSettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pim_server_settings", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        session = RecordingAuthSessionStore()
        settings = ServerSettingsStore(context, session)
        settings.setBaseUrl("${origin()}/api/v1/")
        session.save("native-access", "native-refresh", Long.MAX_VALUE - 1, origin())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `no page header is copied even without AuthInterceptor masking Authorization`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("{}"))
        val proxy = NativeEmbedRequestProxy(OkHttpClient.Builder().build(), session, settings)
        val response = proxy.execute(
            EmbedRequestDecision.Proxy("${origin()}/api/v1/mobile/location/analytics/overview".toHttpUrl()),
            mapOf(
                "Authorization" to listOf("Bearer page-canary"),
                "Referer" to listOf("${origin()}/embed/android/today"),
                "Origin" to listOf(origin()),
                "X-Page-Only" to listOf("page-value")
            )
        )

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertNull(recorded!!.getHeader("Authorization"))
        assertNull(recorded.getHeader("Referer"))
        assertNull(recorded.getHeader("Origin"))
        assertNull(recorded.getHeader("X-Page-Only"))
        response.body.close()
    }

    @Test
    fun `safe body still makes a reentrant read fail before writing`() {
        lateinit var scanner: EmbedResponseScanner
        val innerBuffer = ByteArray(32)
        val innerOutcome = AtomicReference<Result<Int>?>(null)
        var armed = false
        scanner = EmbedResponseScanner(
            upstream = ByteArray(70_000) { 'a'.code.toByte() }.toResponseBody(null),
            needleSource = {
                if (armed) {
                    armed = false
                    innerOutcome.set(runCatching { scanner.read(innerBuffer) })
                }
                emptyList()
            },
            initialNeedles = emptyList(),
            onClosed = { }
        )
        assertFalse(scanner.prefill())
        armed = true

        assertTrue(scanner.read(ByteArray(32)) > 0)

        val inner = innerOutcome.get()
        assertNotNull("control must actually attempt the nested read", inner)
        assertNotNull("a reentrant read must throw without relying on a token match", inner!!.exceptionOrNull())
        assertArrayEquals(ByteArray(32), innerBuffer)
        scanner.close()
    }

    @Test
    fun `scanner close directly closes the upstream source`() {
        val upstreamClosed = AtomicBoolean(false)
        val delegate = "plain-body".toResponseBody(null)
        val trackingBody = object : ResponseBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength(): Long = delegate.contentLength()
            override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
                override fun close() {
                    upstreamClosed.set(true)
                    super.close()
                }
            }.buffer()
        }
        val scanner = EmbedResponseScanner(trackingBody, { emptyList() }, emptyList()) { }

        scanner.close()

        assertTrue(upstreamClosed.get())
    }

    private fun origin(): String = "http://127.0.0.1:${server.port}"
}
