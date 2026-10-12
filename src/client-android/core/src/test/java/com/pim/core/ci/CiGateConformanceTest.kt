package com.pim.core.ci

import com.pim.core.contract.RepoLayout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI / 发布门禁的一致性测试（F-A-01、F-A-02、F-A-04、F-A-05、F-A-06、F-V-03 与 REQ-COM-04）。
 *
 * 判据取自审计 findings 的 `acceptance` 字段与 `remediation/plan.md` 的 AC-COM-02/03/04/05，
 * 判据形态按第二轮统筹裁决（`coordination-round2.md` D-1..D-5）修正：
 *
 * - F-A-02：CI 只跑 `:app:testDebugUnitTest`，`:core` 的测试从不执行 → 断言「每个构建工作流的
 *   单元测试命令覆盖该 Gradle 工程里所有含 `src/test` 的模块」。
 * - F-A-01：发布纯逻辑在 PR 上恒被跳过 → 断言「`CI Gate` 传递依赖里必须有一个按语义识别的、
 *   无 `contents: write` 且真正生成/校验 version.json 的 job」（不绑脚本名）。
 * - REQ-COM-04：release 的 `if` 在 failure/cancelled/skipped 三态下都必须不启动，且全成功时必须能启动
 *   （真实表达式求值，接受 `needs:[gate]` 等语义等价写法）。
 * - F-A-04 / C-1：验签步骤的失败必须在它声明的 shell 语义下传播（`shell: bash` 或脚本内 pipefail）。
 * - F-A-04 / C-3 / D-1：正式发布标志必须由 `is_release` 推导并传给两个可复用 workflow，
 *   在两个 workflow 的签名相关 step 上生效（防「只改脚本不改接线」的假绿）。
 * - F-A-05：文档引用的仓库路径必须存在；docs-only 声明必须存在且与 ci.yml 触发条件一致。
 * - F-A-06 / F-V-03：版本自测脚本与契约新鲜度校验必须进 CI。
 */
class CiGateConformanceTest {

    private val root = RepoLayout.root()

    @Test
    fun `构建工作流的单元测试命令覆盖各自 Gradle 工程里所有含测试的模块`() {
        assertEmpty(CiGateChecks.unitTestCoverage(root), "CI 单元测试覆盖不足（REQ-AND-01/AC-AND-01：app+core 测试都要进 gate）")
    }

    @Test
    fun `版本自测脚本必须由 CI 执行`() {
        assertEmpty(CiGateChecks.versionSelfTestInCi(root), "F-A-06")
    }

    @Test
    fun `CI Gate 必须消费一个无写权限的发布纯逻辑校验 job`() {
        assertEmpty(CiGateChecks.releasePureLogicCoveredByGate(root), "F-A-01 / REQ-COM-04")
    }

    @Test
    fun `发布作业不得在任一依赖 job 失败 取消或跳过时启动`() {
        assertEmpty(CiGateChecks.releaseBlocksBadNeeds(root), "REQ-COM-04")
    }

    @Test
    fun `CI Gate 必须逐 need 真正消费每个依赖 job 的结果`() {
        assertEmpty(
            CiGateChecks.gateConsumesRequiredJobs(root),
            "RED-2：只把 job 挂进 needs、脚本不读它的 result 时，required check 仍是绿的",
        )
    }

    @Test
    fun `签名校验失败必须在步骤声明的 shell 语义下传播`() {
        assertEmpty(CiGateChecks.apksignerFailurePropagates(root), "F-A-04 / C-1")
    }

    @Test
    fun `正式发布标志必须由 is_release 推导并传给两个可复用构建工作流`() {
        assertEmpty(CiGateChecks.signingFlagWiring(root), "F-A-04 / C-3 / D-1（app 与 shell 两套签名都要覆盖）")
    }

    @Test
    fun `文档引用的仓库内路径必须存在`() {
        assertEmpty(CiGateChecks.docReferences(root), "F-A-05：断链会误导 agent 按不存在的机制行事")
    }

    @Test
    fun `AGENTS 关于 docs-only 的声明必须存在且与 CI 触发条件一致`() {
        assertEmpty(CiGateChecks.docsOnlyClaimConsistent(root), "F-A-05 / I-6（反真空守卫）")
    }

    @Test
    fun `CI 必须有契约新鲜度门禁`() {
        assertEmpty(CiGateChecks.contractFreshnessGate(root), "F-V-03 / F-V-04")
    }

    @Test
    fun `contract README 声明的 sha256 与快照一致`() {
        assertEmpty(CiGateChecks.contractReadmeHash(root), "契约快照与说明漂移")
    }

    @Test
    fun `模块与测试资产探测本身是有效的`() {
        // 防止「检测不到任何模块」把覆盖断言变成恒真
        assertTrue(
            "src/client-android 下应探测到 :app 与 :core 两个有单元测试的模块",
            CiGateChecks.modulesWithUnitTests(root, "src/client-android").containsAll(setOf("app", "core")),
        )
        assertTrue(
            "src/client-shell-android 下应探测到 :app 模块",
            CiGateChecks.modulesWithUnitTests(root, "src/client-shell-android").contains("app"),
        )
        assertTrue(
            "build-android.yml 应能被识别出至少一条 gradle 调用",
            GradleInvocations.from(Workflow.buildAndroid()).isNotEmpty(),
        )
        assertTrue(
            "任务名到模块的反解必须有效",
            CiGateChecks.moduleOfTestTask(":app:testDebugUnitTest") == "app" &&
                CiGateChecks.moduleOfTestTask(":features:calendar:testDebugUnitTest") == "features/calendar" &&
                CiGateChecks.moduleOfTestTask("assembleRelease") == null,
        )
    }

    // 判据自身的两态自证（「会不会失败」「等价写法是否被接受」）在 `CiGateFaultContrastTest` 里，
    // 全部基于合成 fixture 的修好/破坏两态；本类只断言**真实仓库在修复后必须变绿**的方向。

    private fun assertEmpty(problems: List<String>, label: String) {
        assertTrue("$label\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
