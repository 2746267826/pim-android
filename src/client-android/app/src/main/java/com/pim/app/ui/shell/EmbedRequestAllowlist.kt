package com.pim.app.ui.shell

import com.pim.core.settings.PimServerEndpoints
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/*
 * PW-07a 实施（唯一规格 remediation/native-embed/reviewed-spec.md §3）。
 *
 * 只依赖 OkHttp `HttpUrl` 与 `java.time`。按顺序 R0–R9，任一条失败即 `Skip`：
 * Skip 对 WebView 返回 null，不附带令牌，不发起上游请求。R10 返回的 `Proxy.url`
 * 必须是**同一次** `rawUrl.toHttpUrlOrNull()` 得到的那个对象（执行层只发这个对象）。
 *
 * trustedOrigin 由调用方每次请求现算（`PimServerEndpoints.from(getBaseUrl()).trustedOrigin`），
 * 本对象不缓存任何跨请求状态、不读会话、不读设置。
 */
sealed interface EmbedRequestDecision {
    /** R0–R9 任一条失败：对 WebView 返回 null，不附带令牌，不发起上游请求。 */
    data object Skip : EmbedRequestDecision

    /** R10：执行层只发这个对象里的 HttpUrl。 */
    data class Proxy(val url: HttpUrl) : EmbedRequestDecision
}

object EmbedRequestAllowlist {

    private const val TODAY_DOCUMENT_PATH = "/embed/android/today"
    private const val TRACKS_DOCUMENT_PATH = "/embed/android/tracks"

    private const val OVERVIEW_PATH = "/api/v1/mobile/location/analytics/overview"
    private const val TRACKS_PATH = "/api/v1/mobile/location/analytics/tracks"
    private const val SUMMARY_PATH = "/api/v1/mobile/summary"

    private const val DATE_PARAMETER = "date"

    /** 严格日历日：长度 10、第 5 与第 8 字符是 `-`，且 STRICT 解析成功。 */
    private val STRICT_ISO_DATE: DateTimeFormatter =
        DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)

    fun decide(
        isForMainFrame: Boolean,
        method: String,
        rawUrl: String,
        requestHeaders: Map<String, List<String>>,
        trustedOrigin: String?,
        mainDocumentPath: String?
    ): EmbedRequestDecision {
        // R0：主框架导航仍走现有 shouldOverrideUrlLoading + decidePimWebNavigation。
        if (isForMainFrame) return EmbedRequestDecision.Skip

        // R1：method 逐字等于 GET。
        if (method != "GET") return EmbedRequestDecision.Skip

        // R2：URL 必须能解析成 HttpUrl（非 http/https、畸形串都在这里失败）。
        val url = rawUrl.toHttpUrlOrNull() ?: return EmbedRequestDecision.Skip

        // R3：userinfo 不放行。
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()) {
            return EmbedRequestDecision.Skip
        }

        // R4：fragment 不放行。
        if (url.fragment != null) return EmbedRequestDecision.Skip

        // R5：请求 origin 必须等于本次 trustedOrigin（现算，不缓存）。
        val trusted = trustedOrigin ?: return EmbedRequestDecision.Skip
        val requestOrigin = runCatching { PimServerEndpoints.trustedOriginOf(url) }.getOrNull()
            ?: return EmbedRequestDecision.Skip
        if (requestOrigin != trusted) return EmbedRequestDecision.Skip

        // R6：Referer 必须恰好一个大小写不敏感匹配的头值，且是两条 embed 文档路径之一。
        if (!refererIsTrustedEmbedDocument(requestHeaders, trusted)) return EmbedRequestDecision.Skip

        // R7：Origin 缺失放行；存在时必须恰好一个头值且逐字等于 trustedOrigin。
        if (!originHeaderIsAllowed(requestHeaders, trusted)) return EmbedRequestDecision.Skip

        // R8：主文档快照必须是两条 embed 路径之一。
        if (mainDocumentPath == null || !isEmbedDocumentPath(mainDocumentPath)) {
            return EmbedRequestDecision.Skip
        }

        // R9：只有今天页面真实发出的 3 条 operation。
        if (!operationIsAllowed(url)) return EmbedRequestDecision.Skip

        // R10：执行层只发判定时这一个 canonical HttpUrl。
        return EmbedRequestDecision.Proxy(url)
    }

    private fun isEmbedDocumentPath(path: String): Boolean =
        path == TODAY_DOCUMENT_PATH || path == TRACKS_DOCUMENT_PATH

    private fun headerValues(requestHeaders: Map<String, List<String>>, name: String): List<String> =
        requestHeaders.entries
            .filter { it.key.equals(name, ignoreCase = true) }
            .flatMap { it.value }

    private fun refererIsTrustedEmbedDocument(
        requestHeaders: Map<String, List<String>>,
        trustedOrigin: String
    ): Boolean {
        val values = headerValues(requestHeaders, "referer")
        if (values.size != 1) return false
        val raw = values.first()
        if (raw.isBlank()) return false
        val referer = raw.toHttpUrlOrNull() ?: return false
        if (referer.encodedUsername.isNotEmpty() || referer.encodedPassword.isNotEmpty()) return false
        val refererOrigin = runCatching { PimServerEndpoints.trustedOriginOf(referer) }.getOrNull()
            ?: return false
        if (refererOrigin != trustedOrigin) return false
        // 比较的是 HttpUrl 归一化后的路径，尾随斜杠不归一化。
        return isEmbedDocumentPath(referer.encodedPath)
    }

    private fun originHeaderIsAllowed(
        requestHeaders: Map<String, List<String>>,
        trustedOrigin: String
    ): Boolean {
        val values = headerValues(requestHeaders, "origin")
        if (values.isEmpty()) return true
        if (values.size != 1) return false
        return values.first() == trustedOrigin
    }

    private fun operationIsAllowed(url: HttpUrl): Boolean {
        return when (url.encodedPath) {
            OVERVIEW_PATH, TRACKS_PATH -> url.encodedQuery == null
            SUMMARY_PATH -> {
                if (url.encodedQuery == null) return false
                if (url.querySize != 1) return false
                if (url.queryParameterName(0) != DATE_PARAMETER) return false
                val value = url.queryParameter(DATE_PARAMETER) ?: return false
                isStrictCalendarDay(value)
            }
            else -> false
        }
    }

    private fun isStrictCalendarDay(value: String): Boolean {
        if (value.length != 10) return false
        if (value[4] != '-' || value[7] != '-') return false
        return runCatching { LocalDate.parse(value, STRICT_ISO_DATE) }.isSuccess
    }
}
