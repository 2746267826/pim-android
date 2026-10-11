package com.pim.app.ui.shell

import android.util.Log
import com.pim.core.auth.AuthSessionStore
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/*
 * PW-07a 实施（唯一规格 remediation/native-embed/reviewed-spec.md §4、§5、§6）。
 *
 * 每个 WebView 一份（建在 `PimWebViewScreen` 里，不放进 ViewModel）：
 * 允许表 → 派生客户端代发 → 响应头剥离 → 单一扫描器。
 *
 * - 派生客户端保留父客户端的拦截器链与 Dispatcher（同一引用），父客户端已带
 *   `AuthInterceptor`；不另写刷新。
 * - 不复制页面的任何请求头：上游请求只有方法 GET 与允许表判定出的那个 URL。
 * - 取消只 `Call.cancel()` 本代理登记的 call，并关闭已交给 WebView 的流；
 *   禁止 `dispatcher.cancelAll()`。
 * - `shouldInterceptRequest` 路径不做 View 调用；日志只有固定原因码 + origin/path，
 *   不记异常消息、头值、正文或令牌。
 */
class NativeEmbedRequestProxy(
    private val parentClient: OkHttpClient,
    private val authSessionStore: AuthSessionStore,
    private val serverSettingsStore: ServerSettingsStore
) {
    /** 规格 §4：不跟随重定向、30 秒 callTimeout、保留父客户端拦截器与 Dispatcher。 */
    val derivedClient: OkHttpClient = parentClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 规格 §3「主文档快照」：`onPageStarted` 在 UI 线程写入，拦截线程只读。 */
    @Volatile
    var mainDocumentPath: String? = null
        private set

    /** 规格 §3：与 `reloadKey`/文档键绑定的代次。 */
    @Volatile
    var generation: Long = 0L
        private set

    private val inFlight = ConcurrentHashMap.newKeySet<RequestSlot>()
    private val deliveredStreams = ConcurrentHashMap.newKeySet<EmbedResponseScanner>()

    private class RequestSlot(val call: Call) {
        @Volatile
        var cancelled: Boolean = false
    }

    /** 规格 §3：`onPageStarted` 在 UI 线程把 URL 解析成 canonical `encodedPath` 写入快照。 */
    fun onMainDocumentStarted(url: String?) {
        mainDocumentPath = url?.toHttpUrlOrNull()?.encodedPath
    }

    /**
     * 规格 §3：`reloadKey` 变化时先取消该 WebView 的在飞 call，再把快照代次加一。
     * 响应返回时代次不一致则不交回正文。
     */
    fun onDocumentReloaded() {
        cancel()
        generation += 1
    }

    /**
     * WebView 入口（规格 §4）：现算 trustedOrigin → `EmbedRequestAllowlist.decide` →
     * Skip 返回 null（不附带令牌、不发起上游请求）→ 否则代发。
     */
    fun proxyRequest(
        rawUrl: String,
        method: String,
        isForMainFrame: Boolean,
        pageRequestHeaders: Map<String, List<String>>
    ): EmbedProxyResponse? {
        val trustedOrigin = currentTrustedOrigin()
        val decision = EmbedRequestAllowlist.decide(
            isForMainFrame = isForMainFrame,
            method = method,
            rawUrl = rawUrl,
            requestHeaders = pageRequestHeaders,
            trustedOrigin = trustedOrigin,
            mainDocumentPath = mainDocumentPath
        )
        return when (decision) {
            EmbedRequestDecision.Skip -> {
                logReason("skip", rawUrl)
                null
            }
            is EmbedRequestDecision.Proxy -> execute(decision, pageRequestHeaders)
        }
    }

    /** 执行层（规格 §4.1）：只发 `proxy.url`，不读也不复制页面请求头。 */
    @Suppress("UNUSED_PARAMETER")
    fun execute(
        proxy: EmbedRequestDecision.Proxy,
        pageRequestHeaders: Map<String, List<String>>
    ): EmbedProxyResponse {
        // 规格 §4：上游请求只有方法 GET 与允许表判定出的 URL；页面请求头一律不复制。
        val requestGeneration = generation
        val needlesBeforeExecute = currentNeedles()
        val slot = RequestSlot(derivedClient.newCall(Request.Builder().url(proxy.url).get().build()))
        inFlight.add(slot)
        try {
            val response = slot.call.execute()
            if (slot.cancelled || requestGeneration != generation) {
                logReason("stale_or_cancelled", proxy.url.toString())
                response.close()
                return EmbedProxyResponse.empty502()
            }
            return deliver(response, slot, requestGeneration, needlesBeforeExecute)
        } catch (failure: Exception) {
            // 规格 §4.1：必须 catch (Exception)。只抓 IOException 会让 HttpException 冲出
            // shouldInterceptRequest。日志不记异常消息（可能含 URL/令牌）。
            logReason("upstream_exception", proxy.url.toString())
            return EmbedProxyResponse.empty502()
        } finally {
            inFlight.remove(slot)
        }
    }

    /** 规格 §4：只取消本代理登记的 call，并关闭已交给 WebView 的流。禁止 `cancelAll()`。 */
    fun cancel() {
        for (slot in inFlight) {
            slot.cancelled = true
            runCatching { slot.call.cancel() }
        }
        for (stream in deliveredStreams.toList()) {
            runCatching { stream.close() }
        }
    }

    private fun deliver(
        response: Response,
        slot: RequestSlot,
        requestGeneration: Long,
        needlesBeforeExecute: List<ByteArray>
    ): EmbedProxyResponse {
        val code = response.code
        // 规格 §5.1：解压后仍能读到 Content-Encoding → 整次空 502，不把压缩字节交给 WebView。
        if (response.header("Content-Encoding") != null) {
            logReason("content_encoding", response.request.url.toString())
            response.close()
            return EmbedProxyResponse.empty502()
        }
        // 规格 §6 / §4.1：只允许 200–299 与 400–599；3xx、1xx 变成空 502。
        if (code !in 200..299 && code !in 400..599) {
            logReason("status_not_deliverable", response.request.url.toString())
            response.close()
            return EmbedProxyResponse.empty502()
        }
        val headers = filterHeaders(response.headers)
        if (headersContainNeedle(headers, needlesBeforeExecute)) {
            logReason("header_scan_hit", response.request.url.toString())
            response.close()
            return EmbedProxyResponse.empty502()
        }
        val body = response.body
        if (body == null) {
            return EmbedProxyResponse(
                statusCode = code,
                reasonPhrase = reasonPhrase(code, response.message),
                mimeType = "application/json",
                headers = headers,
                body = ByteArrayInputStream(ByteArray(0))
            )
        }
        val scanner = EmbedResponseScanner(
            upstream = body,
            needleSource = ::currentNeedles,
            initialNeedles = needlesBeforeExecute,
            onClosed = { deliveredStreams.remove(it) }
        )
        val hitBeforeHandout = try {
            scanner.prefill()
        } catch (failure: Exception) {
            logReason("prefill_exception", response.request.url.toString())
            scanner.close()
            return EmbedProxyResponse.empty502()
        }
        if (hitBeforeHandout) {
            logReason("scan_hit_before_handout", response.request.url.toString())
            scanner.close()
            return EmbedProxyResponse.empty502()
        }
        if (slot.cancelled || requestGeneration != generation) {
            logReason("stale_or_cancelled", response.request.url.toString())
            scanner.close()
            return EmbedProxyResponse.empty502()
        }
        deliveredStreams.add(scanner)
        return EmbedProxyResponse(
            statusCode = code,
            reasonPhrase = reasonPhrase(code, response.message),
            mimeType = mimeTypeOf(response),
            headers = headers,
            body = scanner
        )
    }

    private fun currentTrustedOrigin(): String? = runCatching {
        PimServerEndpoints.from(serverSettingsStore.getBaseUrl()).trustedOrigin
    }.getOrNull()

    /** 规格 §5.2：针集 = 会话 access/refresh 令牌的 UTF-8 字节；空白不是针；没有最短长度。 */
    private fun currentNeedles(): List<ByteArray> {
        val tokens = authSessionStore.snapshot().tokens ?: return emptyList()
        return listOf(tokens.accessToken, tokens.refreshToken)
            .filter { it.isNotBlank() }
            .map { it.toByteArray(Charsets.UTF_8) }
    }

    private fun filterHeaders(headers: Headers): Map<String, List<String>> {
        val names = LinkedHashMap<String, String>()
        val values = LinkedHashMap<String, MutableList<String>>()
        for (index in 0 until headers.size) {
            val rawName = headers.name(index)
            val key = rawName.lowercase(Locale.ROOT)
            if (isDeniedHeader(key)) continue
            names.putIfAbsent(key, rawName)
            values.getOrPut(key) { mutableListOf() }.add(headers.value(index))
        }
        val result = LinkedHashMap<String, List<String>>()
        for ((key, value) in values) {
            result[names[key] ?: key] = value
        }
        return result
    }

    private fun isDeniedHeader(lowercaseName: String): Boolean =
        lowercaseName in DENIED_HEADERS || lowercaseName.startsWith(ACCESS_CONTROL_PREFIX)

    private fun headersContainNeedle(
        headers: Map<String, List<String>>,
        beforeExecute: List<ByteArray>
    ): Boolean {
        val needles = buildList {
            addAll(beforeExecute)
            addAll(currentNeedles())
        }.distinctBy { it.toList() }
        if (needles.isEmpty()) return false
        for (value in headers.values.flatten()) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            for (needle in needles) {
                if (containsBytes(bytes, needle)) return true
            }
        }
        return false
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun mimeTypeOf(response: Response): String {
        val contentType = response.body?.contentType() ?: return "application/json"
        val type = contentType.type
        val subtype = contentType.subtype
        if (type.isBlank() || subtype.isBlank()) return "application/json"
        return "$type/$subtype".lowercase(Locale.ROOT)
    }

    /** 规格 §6：reason 非空、长度 ≤ 128、每个字符都在 0x20..0x7E，否则用标准英文 reason。 */
    private fun reasonPhrase(code: Int, upstreamReason: String?): String {
        val candidate = upstreamReason?.trim().orEmpty()
        if (candidate.isNotEmpty() && candidate.length <= MAX_REASON_LENGTH &&
            candidate.all { it.code in 0x20..0x7E }
        ) {
            return candidate
        }
        return STANDARD_REASONS[code] ?: if (code in 200..299) "OK" else "Error"
    }

    /** 规格 §4：日志只有固定原因码加 origin 与 path，不记异常消息、头值、正文或令牌。 */
    private fun logReason(reason: String, rawUrl: String) {
        val url = rawUrl.toHttpUrlOrNull() ?: return
        val origin = runCatching { PimServerEndpoints.trustedOriginOf(url) }.getOrNull() ?: return
        Log.w(TAG, "embed-proxy $reason origin=$origin path=${url.encodedPath}")
    }

    companion object {
        const val CALL_TIMEOUT_SECONDS: Long = 30L

        private const val TAG = "PimWebView"
        private const val ACCESS_CONTROL_PREFIX = "access-control-"
        private const val MAX_REASON_LENGTH = 128

        private val DENIED_HEADERS = setOf(
            "set-cookie",
            "set-cookie2",
            "content-encoding",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "trailer",
            "upgrade",
            "te",
            "authorization",
            "www-authenticate",
            "proxy-authenticate",
            "proxy-authorization"
        )

        private val STANDARD_REASONS = mapOf(
            200 to "OK",
            201 to "Created",
            204 to "No Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            409 to "Conflict",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout"
        )
    }
}

/** 代发结果：交给 WebView 的状态、reason、mime、头与正文流（规格 §4.1 / §6）。 */
class EmbedProxyResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val mimeType: String,
    val headers: Map<String, List<String>>,
    val body: InputStream
) {
    companion object {
        /** 规格 §6「空 502」：状态 502、reason `Bad Gateway`、mime `application/json`、空流、头表为空。 */
        fun empty502(): EmbedProxyResponse = EmbedProxyResponse(
            statusCode = 502,
            reasonPhrase = "Bad Gateway",
            mimeType = "application/json",
            headers = emptyMap(),
            body = ByteArrayInputStream(ByteArray(0))
        )
    }
}
