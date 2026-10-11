package com.pim.app.ui.shell

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * PW-07a RED Round 2 判据对照（C2 / I1 的最小纠正是否真有牙齿）。
 *
 * 这**不是**产品行为测试，也不证明任何业务已实现：RED-0 接缝恒空 502、没有可拆的实现，
 * 所以这里用**模拟缺陷**来反证纠正后的判据能抓住被禁形态，同时用**正例**证明判据没有过度收紧。
 *
 * | 用例 | 反证的缺陷 | 对应原漏洞 |
 * |---|---|---|
 * | C-P14a | 两段式前缀扫描：先原样交出前 65536 字节（含跨 64KiB 针的前 6 字节），再抛 IOException | 旧写法 `runCatching { readAll }.getOrNull() ?: ByteArray(0)` 把已交出字节抹成空 → P14 空洞通过 |
 * | C-P14b | 正例：安全前缀 65530 字节 + IOException | 新判据接受「交出 ≤65530 且不含针」，没有把允许形态判红 |
 * | C-P19a | 共享 `Dispatcher` 上 `dispatcher.cancelAll()` | 旧 P19 在 cancel 之后才发起另一个 call，cancelAll 抓不到 |
 * | C-P19b | 同一个 `cancelAll()` 之后**新发起**的 call 仍是 200 | 直接证明旧 P19 的时序（cancel 后新 call）不能作为隔离证据 |
 *
 * 只用 MockWebServer 随机端口；不碰 127.0.0.1:5858、不读凭据、不连生产。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedCriteriaControlTest {

    // ── C2：P14 判据（64KiB 跨界针） ────────────────────────────

    /**
     * 与 `NativeEmbedRequestProxyTest.P14` 完全相同的接受条件：502 时空正文，
     * 否则「不含跨 64KiB 的针」且「交出字节 ≤ 65530」。
     */
    private fun p14CriteriaAccepts(statusCode: Int, received: ByteArray): Boolean =
        if (statusCode == 502) {
            received.isEmpty()
        } else {
            received.size <= 65530 && !containsBytes(received, CANARY16.toByteArray())
        }

    @Test
    fun `C-P14a banned two-stage prefix scan is caught by the corrected P14 criteria`() {
        // 被禁形态交出的正是前 65536 字节：65530 个安全字节 + 针的前 6 字节
        val handedOutPrefix = filler(65530, 'a') + CANARY16.substring(0, 6).toByteArray()
        assertEquals("被禁形态交出的切片必须是 65536 字节", 65_536, handedOutPrefix.size)

        val (received, failure) = readAllKeepingBytesOnFailure(scriptedStream(handedOutPrefix))
        assertEquals("累计式读取必须保留已经交出的 65536 字节", 65_536, received.size)
        assertTrue("流出错原因必须是 IOException，实际 $failure", failure is IOException)
        assertTrue(
            "交出的切片里必须真的含针的前缀（否则这条对照没有反证力）",
            containsBytes(received, CANARY16.substring(0, 6).toByteArray())
        )
        assertFalse(
            "纠正后的 P14 判据必须判红「先交出 64KiB 再扫剩余」",
            p14CriteriaAccepts(200, received)
        )

        // 旧写法：把已经交出的字节抹成空数组 → 判据空洞通过（这就是 C2 指出的漏洞）
        val oldStyle = runCatching { readAll(scriptedStream(handedOutPrefix)) }.getOrNull() ?: ByteArray(0)
        assertEquals("旧写法在 IOException 后只能看到 0 字节", 0, oldStyle.size)
        assertTrue(
            "旧写法会让同一条被禁形态空洞通过 —— 这就是 C2 的漏洞本身",
            p14CriteriaAccepts(200, oldStyle)
        )
    }

    @Test
    fun `C-P14b safe prefix of 65530 bytes followed by IOException is accepted`() {
        val safePrefix = filler(65530, 'a')

        val (received, failure) = readAllKeepingBytesOnFailure(scriptedStream(safePrefix))
        assertEquals("正例必须交出全部安全前缀", 65_530, received.size)
        assertTrue("正例的流出错原因也必须是 IOException，实际 $failure", failure is IOException)
        assertFalse("安全前缀里不得含针", containsBytes(received, CANARY16.toByteArray()))
        assertTrue(
            "交出 ≤65530 字节的安全前缀后抛 IOException 是规格允许的形态，判据必须接受",
            p14CriteriaAccepts(200, received)
        )
    }

    // ── I1：取消隔离判据（共享 Dispatcher） ───────────────────────

    @Test
    fun `C-P19a shared dispatcher cancelAll kills a concurrently in-flight other call`() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start()
        serverB.start()
        try {
            val dispatcher = Dispatcher()
            val client = OkHttpClient.Builder().dispatcher(dispatcher).build()
            serverA.enqueue(
                MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS).setBody("""{"a":1}""")
            )
            serverB.enqueue(
                MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS).setBody("""{"b":true}""")
            )

            val aOutcome = AtomicReference<Result<Response>?>(null)
            val bOutcome = AtomicReference<Result<Response>?>(null)
            val a = Thread {
                aOutcome.set(runCatching { client.newCall(Request.Builder().url(serverA.url("/a")).build()).execute() })
            }
            val b = Thread {
                bOutcome.set(runCatching { client.newCall(Request.Builder().url(serverB.url("/b")).build()).execute() })
            }
            a.start()
            awaitUpstream(serverA, 1)
            b.start()
            awaitUpstream(serverB, 1)

            dispatcher.cancelAll()
            a.join(5_000)
            b.join(5_000)

            assertNotNull(aOutcome.get())
            assertNotNull("另一个 call 必须在 cancelAll 之前就已在飞", bOutcome.get())
            assertTrue("cancelAll 必须能杀掉并发的第一个 call，实际 ${aOutcome.get()}", aOutcome.get()!!.isFailure)
            assertTrue(
                "共享 Dispatcher 上的 cancelAll 会把并发的另一个 call（响应头仍在延迟 = execute 尚未返回）" +
                    "一起掐断，所以「另一个 WebView 仍须 200 + 完整正文」的新 P19 判据必然判红，实际 ${bOutcome.get()}",
                bOutcome.get()!!.isFailure
            )
        } finally {
            runCatching { serverA.shutdown() }
            runCatching { serverB.shutdown() }
        }
    }

    @Test
    fun `C-P19b cancelAll leaves a later-started call at 200 so the old P19 shape could not see it`() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start()
        serverB.start()
        try {
            val dispatcher = Dispatcher()
            val client = OkHttpClient.Builder().dispatcher(dispatcher).build()
            serverA.enqueue(
                MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS).setBody("""{"a":1}""")
            )

            val aOutcome = AtomicReference<Result<Response>?>(null)
            val a = Thread {
                aOutcome.set(runCatching { client.newCall(Request.Builder().url(serverA.url("/a")).build()).execute() })
            }
            a.start()
            awaitUpstream(serverA, 1)

            dispatcher.cancelAll()
            a.join(5_000)
            assertTrue(aOutcome.get()!!.isFailure)

            // 旧 P19 的时序：cancel 之后才发起另一个 WebView 的 call
            serverB.enqueue(MockResponse().setResponseCode(200).setBody("""{"b":true}"""))
            val late = client.newCall(Request.Builder().url(serverB.url("/b")).build()).execute()
            assertEquals(
                "cancelAll 之后新发起的 call 不受影响 → 旧 P19 的时序拿不到隔离证据",
                200,
                late.code
            )
            assertEquals("""{"b":true}""", late.body!!.string())
        } finally {
            runCatching { serverA.shutdown() }
            runCatching { serverB.shutdown() }
        }
    }

    // ── helpers ─────────────────────────────────────────────────

    /** 交出 `bytes` 后按 `failWith` 结束：`true` 抛 `IOException`（规格允许的命中失败形态），`false` 正常 EOF。 */
    private fun scriptedStream(bytes: ByteArray, failWith: Boolean = true): InputStream =
        object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position < bytes.size) return bytes[position++].toInt() and 0xFF
                if (failWith) throw IOException("scripted scanner failure after ${bytes.size} bytes")
                return -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= bytes.size) {
                    if (failWith) throw IOException("scripted scanner failure after ${bytes.size} bytes")
                    return -1
                }
                val n = minOf(len, bytes.size - position)
                bytes.copyInto(b, off, position, position + n)
                position += n
                return n
            }
        }

    private fun awaitUpstream(target: MockWebServer, count: Int) {
        val deadline = System.currentTimeMillis() + 4_000
        while (target.requestCount < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(
            "对照设施必须先在共享 Dispatcher 上收到 $count 次请求，实际 ${target.requestCount}",
            count,
            target.requestCount
        )
    }

    private fun filler(size: Int, ch: Char): ByteArray = ByteArray(size) { ch.code.toByte() }

    private companion object {
        const val CANARY16 = "canary-16-bytes!"
    }
}
