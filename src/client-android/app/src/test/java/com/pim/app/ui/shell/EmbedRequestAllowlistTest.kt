package com.pim.app.ui.shell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/*
 * PW-07a §12.2 允许表 RED（唯一规格 remediation/native-embed/reviewed-spec.md §3）。
 *
 * 证据等级：真实 `EmbedRequestAllowlist.decide` + 真实 OkHttp HttpUrl + 真实
 * `PimServerEndpoints`（模块内 JVM）。不打桩判定逻辑。
 *
 * RED-0（§12.1）下 `decide` 恒为 `Skip`，因此：
 * - A1–A7 是**有效 RED**：期待 Proxy，实际 Skip（失败文本含 expected Proxy / actual Skip）；
 * - N1–N2x 是 rule-guard：恒 Skip 的接缝上先通过，实施后的故障对照（去掉 R6/R7/R8/R0、
 *   放开额外 query、用形状正则代替严格日历）必须让对应用例变红。它们不是“已实现”的证据。
 */
class EmbedRequestAllowlistTest {

    // ── A：正例（今天必须失败） ─────────────────────────────────

    @Test
    fun `A1 overview without query on trusted origin with embed referer is proxied`() {
        assertProxy(OVERVIEW, "A1")
    }

    @Test
    fun `A2 tracks without query is proxied`() {
        assertProxy(TRACKS, "A2", referer = TRACKS_PATH)
    }

    @Test
    fun `A3 summary with strict calendar date is proxied`() {
        assertProxy("$SUMMARY?date=2024-02-29", "A3")
    }

    @Test
    fun `A4 percent-encoded date parameter name decodes to date and is proxied with the same canonical url`() {
        assertProxy("$SUMMARY?%64ate=2024-02-29", "A4")
    }

    @Test
    fun `A5 origin header equal to trusted origin is accepted`() {
        assertProxy(OVERVIEW, "A5", origin = TRUSTED_ORIGIN)
    }

    @Test
    fun `A6 header name matching is case insensitive`() {
        assertProxy(OVERVIEW, "A6", rawRefererHeaderName = "ReFerEr")
    }

    @Test
    fun `A7 summary on tracks document is proxied`() {
        assertProxy("$SUMMARY?date=2026-10-11", "A7", referer = TRACKS_PATH, mainDocumentPath = TRACKS_PATH)
    }

    // ── R0：主框架 ──────────────────────────────────────────────

    @Test
    fun `N-R0 main frame request is skipped even when everything else is valid`() {
        assertSkip(OVERVIEW, "N-R0", "主框架导航走 shouldOverrideUrlLoading，不代发", isForMainFrame = true)
    }

    // ── R1：方法 ────────────────────────────────────────────────

    @Test
    fun `N-R1 method must be exactly GET`() {
        assertSkip(OVERVIEW, "N-R1a", "POST 不放行", method = "POST")
        assertSkip(OVERVIEW, "N-R1b", "小写 get 不放行", method = "get")
        assertSkip(OVERVIEW, "N-R1c", "带空格的 GET 不放行", method = "GET ")
    }

    // ── R2：URL 解析 ────────────────────────────────────────────

    @Test
    fun `N-R2 unparsable url is skipped`() {
        assertSkip("not a url", "N-R2a", "无法解析")
        assertSkip("://", "N-R2b", "无法解析")
        assertSkip("", "N-R2c", "空串无法解析")
    }

    // ── R3 / R4：userinfo 与 fragment ───────────────────────────

    @Test
    fun `N-R3 url userinfo is skipped`() {
        assertSkip("https://u:p@pim.example/api/v1/mobile/summary?date=2024-02-29", "N-R3", "userinfo 不放行")
    }

    @Test
    fun `N-R4 url fragment is skipped`() {
        assertSkip("$SUMMARY?date=2024-02-29#frag", "N-R4", "fragment 不放行")
        assertSkip("$OVERVIEW#frag", "N-R4b", "fragment 不放行")
    }

    // ── R5：origin ──────────────────────────────────────────────

    @Test
    fun `N-R5 request origin must equal trusted origin`() {
        assertSkip("http://pim.example/api/v1/mobile/location/analytics/overview", "N-R5a", "scheme 不同")
        assertSkip("https://pim.example:8443/api/v1/mobile/location/analytics/overview", "N-R5b", "端口不同")
        assertSkip("https://api.pim.example/api/v1/mobile/location/analytics/overview", "N-R5c", "子域不放行")
        assertSkip("https://evil.test/api/v1/mobile/location/analytics/overview", "N-R5d", "跨源不放行")
    }

    @Test
    fun `N-R5e trusted origin null skips everything`() {
        assertSkip(OVERVIEW, "N-R5e", "服务器地址解析失败时整次 Skip", trustedOrigin = null)
    }

    // ── R6：Referer ─────────────────────────────────────────────

    @Test
    fun `N-R6 referer missing empty or unparsable is skipped`() {
        val headers = refererHeaders(TODAY_PATH)
        assertSkip(OVERVIEW, "N-R6a", "Referer 缺失", headers = headers - "referer")
        assertSkip(OVERVIEW, "N-R6b", "Referer 为空值", headers = mapOf("referer" to listOf("")))
        assertSkip(OVERVIEW, "N-R6c", "Referer 为空列表", headers = mapOf("referer" to emptyList()))
        assertSkip(OVERVIEW, "N-R6d", "Referer 无法解析", headers = mapOf("referer" to listOf("::::")))
    }

    @Test
    fun `N-R6e referer with two values is skipped`() {
        assertSkip(
            OVERVIEW,
            "N-R6e",
            "必须恰好一个头值",
            headers = mapOf("referer" to listOf("https://pim.example$TODAY_PATH", "https://pim.example$TODAY_PATH"))
        )
    }

    @Test
    fun `N-R6f referer with userinfo is skipped`() {
        assertSkip(
            OVERVIEW,
            "N-R6f",
            "Referer 带 userinfo",
            headers = mapOf("referer" to listOf("https://u:p@pim.example$TODAY_PATH"))
        )
    }

    @Test
    fun `N-R6g referer origin must equal trusted origin`() {
        assertSkip(
            OVERVIEW,
            "N-R6g",
            "Referer origin 不同",
            headers = mapOf("referer" to listOf("https://evil.test$TODAY_PATH"))
        )
        assertSkip(
            OVERVIEW,
            "N-R6h",
            "Referer scheme 不同",
            headers = mapOf("referer" to listOf("http://pim.example$TODAY_PATH"))
        )
    }

    @Test
    fun `N-R6i referer path must be one of the two embed paths`() {
        assertSkip(
            OVERVIEW,
            "N-R6i",
            "非 embed 路径",
            headers = mapOf("referer" to listOf("https://pim.example/today"))
        )
        assertSkip(
            OVERVIEW,
            "N-R6j",
            "第三条 embed 路径不放行",
            headers = mapOf("referer" to listOf("https://pim.example/embed/android/other"))
        )
        assertSkip(
            OVERVIEW,
            "N-R6k",
            "尾随斜杠不归一化",
            headers = mapOf("referer" to listOf("https://pim.example$TODAY_PATH/"))
        )
    }

    // ── R7：Origin ──────────────────────────────────────────────

    @Test
    fun `N-R7 origin present must be exactly one value equal to trusted origin`() {
        assertSkip(OVERVIEW, "N-R7a", "Origin 不等", origin = "https://evil.test")
        assertSkip(OVERVIEW, "N-R7b", "Origin 带尾随斜杠", origin = "$TRUSTED_ORIGIN/")
        assertSkip(OVERVIEW, "N-R7c", "Origin 空值", origin = "")
    }

    @Test
    fun `N-R7d origin with two values is skipped`() {
        val headers = refererHeaders(TODAY_PATH) + ("origin" to listOf(TRUSTED_ORIGIN, TRUSTED_ORIGIN))
        assertSkip(OVERVIEW, "N-R7d", "Origin 必须恰好一个头值", headers = headers)
    }

    // ── R8：主文档快照 ──────────────────────────────────────────

    @Test
    fun `N-R8 main document snapshot must be an embed path`() {
        assertSkip(OVERVIEW, "N-R8a", "快照缺失", mainDocumentPath = null)
        assertSkip(OVERVIEW, "N-R8b", "快照是非 embed 路径", mainDocumentPath = "/today")
        assertSkip(OVERVIEW, "N-R8c", "快照是第三条路径", mainDocumentPath = "/embed/android/other")
    }

    // ── R9：operation 与查询 ────────────────────────────────────

    @Test
    fun `N-R9 only the three known operations are allowed`() {
        assertSkip("$API/mobile/location/analytics/summary", "N-R9a", "未知路径")
        assertSkip("$API/mobile/summary/", "N-R9b", "尾随斜杠路径")
        assertSkip("$API/mobile/summaries?date=2024-02-29", "N-R9c", "未知路径")
        assertSkip("$API/mobile/summary%2f?date=2024-02-29", "N-R9d", "summary%2f 不是 summary")
        assertSkip("$API/mobile/summ%61ry?date=2024-02-29", "N-R9e", "路径不做百分号解码")
    }

    @Test
    fun `N-R9f overview and tracks must not carry a query`() {
        assertSkip("$OVERVIEW?x=1", "N-R9f", "overview 带 query")
        assertSkip("$OVERVIEW?", "N-R9g", "overview 空 query")
        assertSkip("$TRACKS?date=2024-02-29", "N-R9h", "tracks 带 query")
    }

    @Test
    fun `N-R9i summary must carry exactly one date parameter`() {
        assertSkip(SUMMARY, "N-R9i", "summary 无 query")
        assertSkip("$SUMMARY?", "N-R9j", "summary 空 query")
        assertSkip("$SUMMARY?date=", "N-R9k", "空日期")
        assertSkip("$SUMMARY?date=2024-02-29&", "N-R9l", "尾随 & 产生第二个空参数")
        assertSkip("$SUMMARY?date=2024-02-29&x=1", "N-R9m", "额外参数不放行")
        assertSkip("$SUMMARY?date=2024-02-29&date=2024-02-29", "N-R9n", "重复参数不放行")
        assertSkip("$SUMMARY?dt=2024-02-29", "N-R9o", "参数名不是 date")
    }

    @Test
    fun `N-R9p date must be a strict calendar day`() {
        assertSkip("$SUMMARY?date=2026-13-40", "N-R9p", "13 月 40 日")
        assertSkip("$SUMMARY?date=2026-02-30", "N-R9q", "2 月 30 日")
        assertSkip("$SUMMARY?date=2026-2-3", "N-R9r", "非零填充")
        assertSkip("$SUMMARY?date=2026-02-29", "N-R9s", "非闰年 2 月 29 日")
        assertSkip("$SUMMARY?date=20261011", "N-R9t", "缺少分隔符")
        assertSkip("$SUMMARY?date=2026-10-11T00:00:00Z", "N-R9u", "带时间")
    }

    @Test
    fun `N-R9v leap day on a leap year is allowed`() {
        // 正例放在这里作为日历规则的对照：严格解析成功必须是 2024-02-29，不是 2026-02-29。
        assertProxy("$SUMMARY?date=2024-02-29", "N-R9v")
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun refererHeaders(path: String, headerName: String = "referer"): Map<String, List<String>> =
        mapOf(headerName to listOf("$TRUSTED_ORIGIN$path"))

    private fun assertProxy(
        rawUrl: String,
        id: String,
        method: String = "GET",
        isForMainFrame: Boolean = false,
        referer: String = TODAY_PATH,
        origin: String? = null,
        rawRefererHeaderName: String = "referer",
        trustedOrigin: String? = TRUSTED_ORIGIN,
        mainDocumentPath: String? = TODAY_PATH
    ) {
        val headers = refererHeaders(referer, rawRefererHeaderName) +
            (if (origin == null) emptyMap() else mapOf("origin" to listOf(origin)))
        val decision = EmbedRequestAllowlist.decide(
            isForMainFrame = isForMainFrame,
            method = method,
            rawUrl = rawUrl,
            requestHeaders = headers,
            trustedOrigin = trustedOrigin,
            mainDocumentPath = mainDocumentPath
        )
        val canonical = rawUrl.toHttpUrlOrNull()
        assertNotNull("$id 用例自身错误：rawUrl 必须能解析", canonical)
        assertEquals(
            "$id 期待 Proxy(${canonical!!})（canonical HttpUrl），实际 $decision",
            EmbedRequestDecision.Proxy(canonical),
            decision
        )
    }

    private fun assertSkip(
        rawUrl: String,
        id: String,
        reason: String,
        method: String = "GET",
        isForMainFrame: Boolean = false,
        headers: Map<String, List<String>> = refererHeaders(TODAY_PATH),
        origin: String? = null,
        trustedOrigin: String? = TRUSTED_ORIGIN,
        mainDocumentPath: String? = TODAY_PATH
    ) {
        val resolvedHeaders = headers + (if (origin == null) emptyMap() else mapOf("origin" to listOf(origin)))
        val decision = EmbedRequestAllowlist.decide(
            isForMainFrame = isForMainFrame,
            method = method,
            rawUrl = rawUrl,
            requestHeaders = resolvedHeaders,
            trustedOrigin = trustedOrigin,
            mainDocumentPath = mainDocumentPath
        )
        assertEquals("$id $reason：期待 Skip，实际 $decision", EmbedRequestDecision.Skip, decision)
    }

    private companion object {
        const val TRUSTED_ORIGIN = "https://pim.example"
        const val API = "$TRUSTED_ORIGIN/api/v1"
        const val OVERVIEW = "$API/mobile/location/analytics/overview"
        const val TRACKS = "$API/mobile/location/analytics/tracks"
        const val SUMMARY = "$API/mobile/summary"
        const val TODAY_PATH = "/embed/android/today"
        const val TRACKS_PATH = "/embed/android/tracks"
    }
}
