package com.pim.core.contract

import com.pim.core.network.ApiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-A-03 / REQ-COM-02 / REQ-AND-01 / AC-AND-01。
 *
 * 以**真实 Retrofit 接口的注解反射**为输入，逐 operation 对照 `contract/openapi.json`。
 * 这不是「源码 contains」测试：路径/方法/query/path 参数/@Body 类型都来自运行期可见的注解，
 * 字段名/类型/可空性来自真实 kotlinx.serialization 描述符（`@SerialName` 之后的 wire 名）。
 *
 * 判据形态（第二轮修正）：
 * - **C-4**：不再用固定魔数（34）当规模哨兵 —— 改为「反射扫描 ⇄ 源码独立扫描」全量对账；
 *   并用已知抽样方法做扫描器有效性守卫（下界，不绑定总数），合法增删端点不会误伤。
 * - **I-1**：在字段名之外补可判定的 **type / enum / nullable / required** 维度；契约缺信息时
 *   记 `NOT-VERIFIED` note，不改契约、不把不可判定当已覆盖。
 */
class ApiContractConformanceTest {

    private val declared by lazy { ApiServiceDeclarations.scan(ApiService::class.java) }
    private val contract by lazy { OpenApiContract.load() }
    private val report by lazy { ContractConformance.check(declared, contract) }

    // ---------- C-4：扫描器有效性与独立对账（不是固定数量） ----------

    @Test
    fun `反射扫描与源码独立扫描全量对账一致`() {
        val source = ApiServiceSourceScan.scan(ApiServiceSourceScan.sourceFile())
        val problems = DeclarationReconciliation.check(declared, source)
        assertTrue(
            "反射与源码两种独立机制必须枚举出完全相同的声明集合：\n" + problems.joinToString("\n") +
                "\n反射 ${declared.size} 条 / 源码 ${source.size} 条",
            problems.isEmpty(),
        )
    }

    @Test
    fun `扫描器有效性守卫 已知抽样方法必须被两种机制同时看到`() {
        val source = ApiServiceSourceScan.scan(ApiServiceSourceScan.sourceFile())
        val sourceNames = source.map { it.functionName }.toSet()
        val reflectedNames = declared.map { it.functionName }.toSet()
        // RED-4：抽样集合必须落在**不在删除计划内**的稳定方法上。原先写死 `uploadStats`，
        // 而它正是已批准的 F-B-03/D-6 删除对象 —— 修好一条正解会打破另一条。
        // 合法删除由「反射 ⇄ 源码全量对账」两侧同向变化保证，不靠固定名单。
        val samples = setOf(
            "login", "refresh", "sendHeartbeat", "uploadMobileLocationsBatch",
            "getClientLatest", "importIcs", "uploadMobileUsage",
        )
        assertTrue(
            "反射扫描漏读已知方法：${samples - reflectedNames}",
            reflectedNames.containsAll(samples),
        )
        assertTrue(
            "源码扫描漏读已知方法：${samples - sourceNames}（对账机制本身失效，不能当作通过）",
            sourceNames.containsAll(samples),
        )
        // 下界（防「探测不到东西」把对账变成恒真）；**不是**固定的总数哨兵。
        assertTrue(
            "反射扫描只看到 ${declared.size} 条声明，低于合理下界 25 —— 扫描逻辑可能失效",
            declared.size >= 25,
        )
        assertEquals(
            "两种机制的声明条数必须一致（逐条一致性由对账测试负责）",
            source.size,
            declared.size,
        )
    }

    // ---------- 路径 / 方法 / 参数 / body 形状 ----------

    @Test
    fun `每个声明路径都存在于权威契约`() {
        assertNoViolations(ContractConformance.RULE_PATH)
    }

    @Test
    fun `每个声明方法与契约 operation 一致`() {
        assertNoViolations(ContractConformance.RULE_METHOD)
    }

    @Test
    fun `路径参数与契约一致`() {
        assertNoViolations(ContractConformance.RULE_PATH_PARAM)
    }

    @Test
    fun `不得声明契约外的查询参数`() {
        assertNoViolations(ContractConformance.RULE_QUERY)
    }

    @Test
    fun `请求体的存在性与契约 requestBody 一致`() {
        assertNoViolations(ContractConformance.RULE_BODY_PRESENCE)
    }

    @Test
    fun `请求体 schema 与契约组件一致（组件名与字段名）`() {
        assertNoViolations(ContractConformance.RULE_BODY_SCHEMA)
    }

    // ---------- I-1：可判定的 type / enum / nullable / required ----------

    @Test
    fun `请求体字段类型与契约 type 一致`() {
        assertNoViolations(ContractConformance.RULE_BODY_TYPE)
    }

    @Test
    fun `请求体字段枚举必须能被契约枚举约束`() {
        assertNoViolations(ContractConformance.RULE_BODY_ENUM)
    }

    @Test
    fun `契约不可空的字段客户端不得可空`() {
        assertNoViolations(ContractConformance.RULE_BODY_NULLABLE)
    }

    @Test
    fun `契约 required 的字段客户端不得可选或可空`() {
        assertNoViolations(ContractConformance.RULE_BODY_REQUIRED)
    }

    @Test
    fun `契约未提供类型信息时必须显式记录 NOT-VERIFIED 而不是静默通过`() {
        // 反真空守卫：报告必须把「契约没给 type/enum」的部分记成 note；
        // 同时 note 里不得把不可判定项写成已覆盖。
        val notes = report.notes.map { it.detail }
        assertTrue(
            "报告应至少包含一条可判定性说明（大小写 note 或 NOT-VERIFIED note），实际 ${notes.size} 条",
            notes.isNotEmpty(),
        )
        assertTrue(
            "NOT-VERIFIED 说明必须带前缀，便于与真实违规区分",
            notes.none { it.contains("NOT-VERIFIED") && !it.startsWith("NOT-VERIFIED") },
        )
    }

    private fun assertNoViolations(rule: String) {
        val violations = report.violationsOf(rule)
        assertTrue(
            "$rule 违规 ${violations.size} 条：\n" + violations.joinToString("\n") +
                "\n\n完整报告：\n${report.render()}",
            violations.isEmpty(),
        )
    }
}
