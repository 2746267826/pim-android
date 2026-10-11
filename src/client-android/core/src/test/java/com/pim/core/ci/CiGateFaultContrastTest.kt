package com.pim.core.ci

import com.pim.core.contract.RepoLayout
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故障对照：证明 `CiGateChecks` 里的每条检查**会在违规时失败**，而不是恰好恒真。
 *
 * 极性规则（C-2）：**不对「真实仓库当前是否违规」做断言**。每条对照都在同一个
 * **合成 fixture**（`CiFixtures.syntheticRepo`，最小但结构完整的工作流）上跑两态：
 *
 *   1. 构造「已修好」形态 → 断言检查通过；
 *   2. 构造性破坏 → 断言同一条检查报出问题。
 *
 * fixture 不粘贴真实文件文本，所以实现阶段修改真实 YAML 之后这些用例仍成立，
 * 不需要「顺手改测试」（这正是上一轮 C-2 的根因）。
 */
class CiGateFaultContrastTest {

    private val settings = mapOf(
        "src/client-android/settings.gradle.kts" to CiFixtures.Templates.SETTINGS_ANDROID,
        "src/client-shell-android/settings.gradle.kts" to CiFixtures.Templates.SETTINGS_SHELL,
    )

    private fun buildRepo(
        label: String,
        includeCore: Boolean = true,
        ci: String,
        buildAndroidFlagInput: String? = null,
        buildAndroidFlagEnv: Boolean = false,
        shellBash: Boolean = false,
        inScriptPipefail: Boolean = false,
        stageApkReadsFlag: Boolean = true,
        extraFiles: Map<String, String> = emptyMap(),
    ): File = CiFixtures.syntheticRepo(
        label = label,
        ci = ci,
        buildAndroid = CiFixtures.Templates.buildWorkflow(
            projectRoot = "src/client-android",
            secretBase = "ANDROID",
            gradleTasks = CiFixtures.Templates.gradlewTasks(includeCore),
            flagInput = buildAndroidFlagInput,
            flagEnv = buildAndroidFlagEnv,
            shellBash = shellBash,
            stageApkReadsFlag = buildAndroidFlagEnv && stageApkReadsFlag,
        ),
        buildShellAndroid = CiFixtures.Templates.buildWorkflow(
            projectRoot = "src/client-shell-android",
            secretBase = "SHELL_ANDROID",
            gradleTasks = CiFixtures.Templates.gradlewTasks(includeCore = false),
            flagInput = buildAndroidFlagInput,
            flagEnv = buildAndroidFlagEnv,
            inScriptPipefail = inScriptPipefail,
        ),
        extraFiles = settings + extraFiles,
    )

    private val okCondition =
        "always() && needs.resolve-version.outputs.is_release == 'true' && " +
            "!contains(needs.*.result, 'failure') && !contains(needs.*.result, 'cancelled') && " +
            "!contains(needs.*.result, 'skipped')"

    // ---------- F-A-02：单元测试覆盖 ----------

    @Test
    fun `单元测试覆盖检查能区分 修好后 与 破坏后`() {
        val dir = buildRepo("coverage", includeCore = true, ci = CiFixtures.Templates.ci(okCondition))
        assertTrue(
            "覆盖 :app 与 :core 后应通过，实际：${CiGateChecks.unitTestCoverage(dir)}",
            CiGateChecks.unitTestCoverage(dir).isEmpty(),
        )
        val broken = buildRepo("coverage-broken", includeCore = false, ci = CiFixtures.Templates.ci(okCondition))
        assertTrue(
            "去掉 :core 后覆盖检查必须报错",
            CiGateChecks.unitTestCoverage(broken).any { it.contains("[core]") },
        )
    }

    // ---------- REQ-COM-04：release 不得在 failure/cancelled/skipped 时启动 ----------

    @Test
    fun `发布作业 needs 检查能接受字面量修法与 gate 等价修法`() {
        // 修法 A：显式排除 failure/cancelled/skipped（字面量写法）
        val literal = buildRepo("release-needs-literal", ci = CiFixtures.Templates.ci(okCondition))
        assertTrue(
            "显式排除三态后应通过，实际：${CiGateChecks.releaseBlocksBadNeeds(literal)}",
            CiGateChecks.releaseBlocksBadNeeds(literal).isEmpty(),
        )
        // 破坏：去掉 skipped
        val missingSkipped = buildRepo(
            "release-needs-missing-skipped",
            ci = CiFixtures.Templates.ci(okCondition.replace(" && !contains(needs.*.result, 'skipped')", "")),
        )
        assertTrue(
            "去掉 skipped 后必须报错（skipped 仍会发版）",
            CiGateChecks.releaseBlocksBadNeeds(missingSkipped).any { it.contains("skipped") },
        )

        // 修法 B（语义等价）：release 只依赖聚合 gate，并要求 gate.result == 'success'
        val equivalent = buildRepo(
            "release-needs-equivalent",
            ci = CiFixtures.Templates.ci(
                releaseCondition = "always() && github.ref == 'refs/heads/master' && needs.gate.result == 'success'",
                releaseNeeds = "gate",
            ),
        )
        assertTrue(
            "needs:[gate] + needs.gate.result == 'success' 语义合规，不应被判红，实际：${CiGateChecks.releaseBlocksBadNeeds(equivalent)}",
            CiGateChecks.releaseBlocksBadNeeds(equivalent).isEmpty(),
        )
        // 破坏等价写法：把 gate 判定改宽（skipped/failure 也能发版）
        val widened = buildRepo(
            "release-needs-widened",
            ci = CiFixtures.Templates.ci(
                releaseCondition = "always() && github.ref == 'refs/heads/master' && needs.gate.result != 'cancelled'",
                releaseNeeds = "gate",
            ),
        )
        assertTrue(
            "把 gate 判定改宽后（skipped/failure 也能发版）必须报错，实际：${CiGateChecks.releaseBlocksBadNeeds(widened)}",
            CiGateChecks.releaseBlocksBadNeeds(widened).isNotEmpty(),
        )

        // 反向守卫：恒假条件也要被拦住（不能把「不可发布」伪装成合规）
        val alwaysFalse = buildRepo(
            "release-needs-false",
            ci = CiFixtures.Templates.ci("github.ref == 'refs/heads/never-released'"),
        )
        assertTrue(
            "release 条件恒假必须被反向守卫报错",
            CiGateChecks.releaseBlocksBadNeeds(alwaysFalse).any { it.contains("反向守卫") },
        )
    }

    // ---------- F-A-01：gate 必须消费发布纯逻辑校验（语义识别，不绑脚本名） ----------

    @Test
    fun `发布纯逻辑门禁检查能识别 接线正确 与 只改脚本没接线`() {
        val scriptPath = "scripts/ci/verify-release-artifacts.sh"
        val fixed = buildRepo(
            "release-pure-logic",
            ci = CiFixtures.Templates.ci(
                okCondition,
                releaseNeeds = "resolve-version, gate",
                extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath),
                gateNeeds = "resolve-version, release-logic, build-android, build-shell-android",
                releaseVersionJsonStep = releaseVersionJsonStep(scriptPath),
            ),
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        val validator = CiGateChecks.findReleasePureLogicValidator(fixed)
        assertNotNull(
            "接线正确的发布纯逻辑 job 应被语义识别到，实际：${CiGateChecks.releasePureLogicCoveredByGate(fixed)}",
            validator,
        )
        assertTrue("应识别到 job id", validator!!.jobId == "release-logic")
        assertTrue(
            "接线正确时判据应通过，实际：${CiGateChecks.releasePureLogicCoveredByGate(fixed)}",
            CiGateChecks.releasePureLogicCoveredByGate(fixed).isEmpty(),
        )

        // 破坏 1：job 存在但 gate 没有消费它 → 合并前没有执行路径
        val unwired = buildRepo(
            "release-pure-logic-unwired",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath),
                gateNeeds = "resolve-version, build-android, build-shell-android",
            ),
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        assertNull("job 未被 CI Gate 消费时不应算作有执行路径", CiGateChecks.findReleasePureLogicValidator(unwired))
        assertTrue(
            "未被 gate 消费时必须报错",
            CiGateChecks.releasePureLogicCoveredByGate(unwired).isNotEmpty(),
        )

        // 破坏 2：接回 gate 但改成 contents: write（等于可以在 PR 上发版）
        val writableCi = CiFixtures.Templates.ci(
            okCondition,
            extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath).replace(
                "    contents: read",
                "    contents: write",
            ),
            gateNeeds = "resolve-version, release-logic, build-android, build-shell-android",
        )
        val writable = buildRepo(
            "release-pure-logic-writable",
            ci = writableCi,
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        assertNull(
            "带 contents: write 的 job 不算「无发布权限的纯逻辑校验」",
            CiGateChecks.findReleasePureLogicValidator(writable),
        )

        // 破坏 3（假绿通道）：只写脚本、不建 job
        val scriptOnly = buildRepo(
            "release-pure-logic-script-only",
            ci = CiFixtures.Templates.ci(okCondition),
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        assertTrue(
            "只有脚本、没有 gate 消费的 job 时必须报错（防只改脚本的假绿）",
            CiGateChecks.releasePureLogicCoveredByGate(scriptOnly).isNotEmpty(),
        )

        // 破坏 4（假绿通道）：入口 step 用 continue-on-error 吞掉失败，job 仍会报 success
        val swallowed = buildRepo(
            "release-pure-logic-continue-on-error",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath).replace(
                    "      run: bash $scriptPath",
                    "      continue-on-error: true\n      run: bash $scriptPath",
                ),
                gateNeeds = "resolve-version, release-logic, build-android, build-shell-android",
            ),
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        val swallowedProblems = CiGateChecks.releasePureLogicCoveredByGate(swallowed)
        assertTrue(
            "continue-on-error 是禁止的假绿写法，必须报错，实际：$swallowedProblems",
            swallowedProblems.any { it.contains("continue-on-error") },
        )
    }

    // ---------- C-1：验签失败传播（按 step 的 shell 语义） ----------

    @Test
    fun `验签失败传播检查承认 shell bash 也承认脚本内 pipefail`() {
        val fixed = buildRepo(
            "apksigner-pipefail",
            ci = CiFixtures.Templates.ci(okCondition),
            shellBash = true,
            inScriptPipefail = true,
        )
        assertTrue(
            "app 用 shell: bash、shell 用脚本内 pipefail（两种等价修法）都应通过，实际：${CiGateChecks.apksignerFailurePropagates(fixed)}",
            CiGateChecks.apksignerFailurePropagates(fixed).isEmpty(),
        )
        val broken = buildRepo(
            "apksigner-default-shell",
            ci = CiFixtures.Templates.ci(okCondition),
            shellBash = false,
            inScriptPipefail = false,
        )
        assertTrue(
            "去掉 shell: bash / pipefail 后必须报错",
            CiGateChecks.apksignerFailurePropagates(broken).any { it.contains("build-android") },
        )
    }

    // ---------- C-3 / D-1：签名标志传递链 ----------

    @Test
    fun `签名标志接线检查能识别 接线正确 与 只改脚本没接线`() {
        val fixed = buildRepo(
            "signing-wiring",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = true),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = true,
        )
        assertTrue(
            "接线正确的 fixture 应通过，实际：${CiGateChecks.signingFlagWiring(fixed)}",
            CiGateChecks.signingFlagWiring(fixed).isEmpty(),
        )

        // 破坏 1：ci.yml 不再把 is_release 传给可复用 workflow
        val unwired = buildRepo(
            "signing-wiring-unwired",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = false),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = true,
        )
        assertTrue(
            "构建作业没有接发布标志时必须报错",
            CiGateChecks.signingFlagWiring(unwired).any { it.contains("build-android") },
        )

        // 破坏 2（假绿通道）：声明与传递都在，但签名 step 不再引用它（只让脚本自己读变量）
        val scriptOnly = buildRepo(
            "signing-wiring-script-only",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = true),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = false,
        )
        assertTrue(
            "只让脚本读环境变量、workflow 不再传标志时必须报错（C-3 假绿通道），实际：${CiGateChecks.signingFlagWiring(scriptOnly)}",
            CiGateChecks.signingFlagWiring(scriptOnly).any { it.contains("Decode signing key") },
        )

        // 破坏 3：`workflow_dispatch` 未声明该输入（手动触发无法显式选择非发布）
        val noDispatch = buildRepo(
            "signing-wiring-no-dispatch",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = true),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = true,
        )
        CiFixtures.rewriteLines(
            noDispatch,
            ".github/workflows/build-shell-android.yml",
            { it.contains("require_signing: { required: false") },
            "      placeholder: { required: false, default: '' }",
        )
        assertTrue(
            "workflow_dispatch 缺该输入时必须报错",
            CiGateChecks.signingFlagWiring(noDispatch).any { it.contains("workflow_dispatch") },
        )
    }

    // ---------- RED-1：签名标志表达式的极性必须两态求值 ----------

    @Test
    fun `签名标志极性必须两态求值 取反 常量 错引用都要被拒绝`() {
        fun wiring(value: String): List<String> = CiGateChecks.signingFlagPolarityProblems("build-android", "require_signing", value)

        val correct = "\${{ needs.resolve-version.outputs.is_release == 'true' }}"
        assertTrue("正极性表达式必须通过，实际：${wiring(correct)}", wiring(correct).isEmpty())

        val negated = "\${{ needs.resolve-version.outputs.is_release != 'true' }}"
        assertTrue("`!=` 反极性必须被拒绝（contains 判定抓不到）", wiring(negated).isNotEmpty())

        val doublyNegated = "\${{ !(needs.resolve-version.outputs.is_release == 'true') }}"
        assertTrue("额外取反必须被拒绝", wiring(doublyNegated).isNotEmpty())

        val constant = "\${{ true }}"
        assertTrue("常量必须被拒绝（不随 is_release 变化）", wiring(constant).isNotEmpty())

        val wrongRef = "\${{ needs.build-android.outputs.is_release == 'true' }}"
        assertTrue("错引用必须被拒绝（无法由 resolve-version 输出判定）", wiring(wrongRef).isNotEmpty())

        val extraTrue = "\${{ needs.resolve-version.outputs.is_release == 'true' || true }}"
        assertTrue("额外常量真值必须被拒绝（非发布态也会得 true）", wiring(extraTrue).isNotEmpty())

        // 真实判据路径：把 fixture 的 with 值改成 `!=` 后，结构性判据必须报错（探针 P1 的回归）。
        val bad = buildRepo(
            "signing-wiring-negated",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = true)
                .replace(
                    "require_signing: \${{ needs.resolve-version.outputs.is_release == 'true' }}",
                    "require_signing: \${{ needs.resolve-version.outputs.is_release != 'true' }}",
                ),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = true,
        )
        assertTrue(
            "极性反置的 fixture 必须被 signingFlagWiring 判红，实际：${CiGateChecks.signingFlagWiring(bad)}",
            CiGateChecks.signingFlagWiring(bad).isNotEmpty(),
        )
    }

    @Test
    fun `签名标志挂在 env 但不被脚本读取必须报错`() {
        val fixed = buildRepo(
            "signing-flag-used",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = true),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = true,
            stageApkReadsFlag = true,
        )
        assertTrue(
            "所有签名 step 都真正读取标志时应通过，实际：${CiGateChecks.signingFlagWiring(fixed)}",
            CiGateChecks.signingFlagWiring(fixed).isEmpty(),
        )
        val unused = buildRepo(
            "signing-flag-unused",
            ci = CiFixtures.Templates.ci(okCondition, passSigningFlag = true),
            buildAndroidFlagInput = "require_signing",
            buildAndroidFlagEnv = true,
            stageApkReadsFlag = false,
        )
        assertTrue(
            "Stage APK 只挂 env 不读变量时必须报错（M-F 假绿通道），实际：${CiGateChecks.signingFlagWiring(unused)}",
            CiGateChecks.signingFlagWiring(unused).any { it.contains("Stage APK") },
        )
    }

    // ---------- RED-2：required gate 必须逐 need 真正消费 ----------

    private val D = CiFixtures.D

    /** 让 fixture 的 gate 真的去检查 `release-logic` 的 result（修好态）。 */
    private fun wireGateToReleaseLogic(dir: File) {
        CiFixtures.patch(
            dir,
            ".github/workflows/ci.yml",
            "          R_SHELL: $D{{ needs.build-shell-android.result }}",
            "          R_SHELL: $D{{ needs.build-shell-android.result }}\n" +
                "          R_LOGIC: $D{{ needs.release-logic.result }}",
        )
        CiFixtures.patch(
            dir,
            ".github/workflows/ci.yml",
            "          check \"build-shell-android\" \"\$R_SHELL\"",
            "          check \"release-logic\" \"\$R_LOGIC\"\n" +
                "          check \"build-shell-android\" \"\$R_SHELL\"",
        )
    }

    @Test
    fun `只把 job 挂进 gate needs 而不读它的 result 必须报错`() {
        val scriptPath = "scripts/ci/verify-release-artifacts.sh"
        val files = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript())

        // 破坏（探针 P2 shape A）：job 在 gate.needs 里，但 gate 脚本仍只检查三个固定 job。
        val unwired = buildRepo(
            "gate-not-consumed",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath),
                gateNeeds = "resolve-version, release-logic, build-android, build-shell-android",
            ),
            extraFiles = files,
        )
        val problems = CiGateChecks.gateConsumesRequiredJobs(unwired)
        assertTrue(
            "gate 没有消费 release-logic 的 result 时必须报错，实际：$problems",
            problems.any { it.contains("release-logic") },
        )

        // 修好态：同一 fixture 上把 gate 脚本补上真实消费 → 必须变绿。
        wireGateToReleaseLogic(unwired)
        assertTrue(
            "gate 真正消费 release-logic 后必须通过，实际：${CiGateChecks.gateConsumesRequiredJobs(unwired)}",
            CiGateChecks.gateConsumesRequiredJobs(unwired).isEmpty(),
        )

        // 反向守卫：所有 need 都 success 时 gate 必须成功（恒假 gate 不算合规）。
        val alwaysFail = buildRepo("gate-always-fail", ci = CiFixtures.Templates.ci(okCondition))
        CiFixtures.patch(
            alwaysFail,
            ".github/workflows/ci.yml",
            "          set -uo pipefail",
            "          set -uo pipefail\n          if true; then exit 1; fi",
        )
        assertTrue(
            "恒假 gate 必须被反向守卫报错",
            CiGateChecks.gateConsumesRequiredJobs(alwaysFail).isNotEmpty(),
        )
    }

    @Test
    fun `gate 未消费的 need 只报该 need 而不产生 all-or-nothing 连带诊断`() {
        val scriptPath = "scripts/ci/verify-release-artifacts.sh"
        val dir = buildRepo(
            "gate-diagnostics",
            ci = CiFixtures.Templates.ci(
                releaseCondition = "always() && needs.gate.result == 'success'",
                releaseNeeds = "gate",
                extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath),
                gateNeeds = "resolve-version, release-logic, build-android, build-shell-android",
            ),
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        val problems = CiGateChecks.releaseBlocksBadNeeds(dir)
        val unconsumed = problems.filter { it.contains("没有消费 need") }
        assertTrue("必须报出「gate 没有消费 release-logic」这一条，实际：$problems", unconsumed.any { it.contains("release-logic") })
        // M-G：只应指向真正未被消费的那个 need，不再对已被 gate 拦住的 job 报「失败仍发版」。
        assertTrue(
            "已被 gate 消费的 job 不应再报「失败仍发版」，实际：$problems",
            problems.none { it.contains("必要性 job build-android=") || it.contains("必要性 job resolve-version=") },
        )
    }

    // ---------- M-C：working-directory 打错不得静默跳过 ----------

    @Test
    fun `单元测试覆盖检查在工程根无法解析时必须报错而不是静默通过`() {
        val bad = CiFixtures.syntheticRepo(
            label = "coverage-bad-workdir",
            ci = CiFixtures.Templates.ci(okCondition),
            buildAndroid = CiFixtures.Templates.buildWorkflow(
                projectRoot = "src/client-android-typo",
                secretBase = "ANDROID",
                gradleTasks = CiFixtures.Templates.gradlewTasks(includeCore = true),
            ),
            extraFiles = mapOf("src/client-android/settings.gradle.kts" to CiFixtures.Templates.SETTINGS_ANDROID),
        )
        assertTrue(
            "working-directory 打错时必须报错（不能静默跳过），实际：${CiGateChecks.unitTestCoverage(bad)}",
            CiGateChecks.unitTestCoverage(bad).any { it.contains("无法定位 Gradle 工程根") },
        )
    }

    // ---------- M-A：docs-only 矛盾句必须不可判定 ----------

    @Test
    fun `docs-only 同时出现两类关键词必须报不可判定`() {
        val contradictory = CiFixtures.syntheticRepo(
            "docs-only-contradictory",
            ci = CiFixtures.Templates.ci(okCondition),
            agents = CiFixtures.Templates.agents(
                "Docs-only merges still skip all platform builds (path-filtered).",
            ),
        )
        val problems = CiGateChecks.docsOnlyClaimConsistent(contradictory)
        assertTrue(
            "「still skip … (path-filtered)」自相矛盾句必须报错（旧实现的 still 优先级把它判成 0 problem），实际：$problems",
            problems.isNotEmpty(),
        )
    }

    // ---------- RED-2 接线：F-A-06 / F-V-03 的 job 必须被 required gate 消费 ----------

    @Test
    fun `版本自测与契约新鲜度必须被 required gate 消费 而不只是存在`() {
        // 破坏：检查存在但没有任何 job 拒绝它 → versionSelfTestInCi/contractFreshnessGate 必须报「没有被 gate 消费」。
        val dangling = CiFixtures.syntheticRepo(
            "gate-dangling-checks",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraJobs = CiFixtures.Templates.danglingCheckJob(),
            ),
        )
        assertTrue(
            "版本自测 job 未被 required gate 消费时必须报错，实际：${CiGateChecks.versionSelfTestInCi(dangling)}",
            CiGateChecks.versionSelfTestInCi(dangling).any { it.contains("没有") || it.contains("消费") },
        )
        assertTrue(
            "契约新鲜度 job 未被 required gate 消费时必须报错，实际：${CiGateChecks.contractFreshnessGate(dangling)}",
            CiGateChecks.contractFreshnessGate(dangling).any { it.contains("消费") },
        )

        // 修好态：同一个 job 既无写权限、又被 gate 真正消费 → 两条都绿。
        val wired = CiFixtures.syntheticRepo(
            "gate-consumed-checks",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraJobs = CiFixtures.Templates.danglingCheckJob(),
                gateNeeds = "resolve-version, ci-script-checks, build-android, build-shell-android",
            ),
        )
        wireGateToCiScriptChecks(wired)
        assertTrue("修好后版本自测应通过，实际：${CiGateChecks.versionSelfTestInCi(wired)}", CiGateChecks.versionSelfTestInCi(wired).isEmpty())
        assertTrue(
            "修好后契约新鲜度应通过，实际：${CiGateChecks.contractFreshnessGate(wired)}",
            CiGateChecks.contractFreshnessGate(wired).isEmpty(),
        )
    }

    private fun wireGateToCiScriptChecks(dir: File) {
        CiFixtures.patch(
            dir,
            ".github/workflows/ci.yml",
            "          R_SHELL: $D{{ needs.build-shell-android.result }}",
            "          R_SHELL: $D{{ needs.build-shell-android.result }}\n" +
                "          R_SCRIPTS: $D{{ needs.ci-script-checks.result }}",
        )
        CiFixtures.patch(
            dir,
            ".github/workflows/ci.yml",
            "          check \"build-shell-android\" \"\$R_SHELL\"",
            "          check \"ci-script-checks\" \"\$R_SCRIPTS\"\n" +
                "          check \"build-shell-android\" \"\$R_SHELL\"",
        )
    }

    // ---------- F-A-05：文档断链 ----------
    @Test
    fun `文档路径检查能识别 补链 与 断链`() {
        val agents = CiFixtures.Templates.agents("Merges to master run the full pipeline (no path filters).")
            .replace("Merges to master run the full pipeline (no path filters).", "见 `scripts/ci/build-release-notes.sh`。")
        val dir = CiFixtures.syntheticRepo("docs-link", ci = CiFixtures.Templates.ci(okCondition), agents = agents)
        assertTrue(
            "被引用脚本不存在时必须报断链，实际：${CiGateChecks.docReferences(dir)}",
            CiGateChecks.docReferences(dir).any { it.contains("build-release-notes.sh") },
        )
        val target = File(dir, "scripts/ci/build-release-notes.sh")
        target.parentFile?.mkdirs()
        target.writeText("#!/usr/bin/env bash\ntrue\n")
        assertTrue(
            "补齐被引用的脚本后该断链应消失，实际：${CiGateChecks.docReferences(dir)}",
            CiGateChecks.docReferences(dir).none { it.contains("build-release-notes.sh") },
        )
        target.delete()
        assertTrue(
            "删掉后必须重新报断链",
            CiGateChecks.docReferences(dir).any { it.contains("build-release-notes.sh") },
        )
    }

    // ---------- F-A-05 / I-6：docs-only 声明 ----------

    @Test
    fun `docs-only 声明检查能识别 已对齐 与 真空删除`() {
        val aligned = CiFixtures.syntheticRepo(
            "docs-only-aligned",
            ci = CiFixtures.Templates.ci(okCondition),
            agents = CiFixtures.Templates.agents(
                "Merges to master always run the full CI pipeline regardless of which files changed " +
                    "(no path filters), so a docs-only merge can still produce a GitHub Release.",
            ),
        )
        assertTrue(
            "改成与 ci.yml 触发条件一致的声明后应通过，实际：${CiGateChecks.docsOnlyClaimConsistent(aligned)}",
            CiGateChecks.docsOnlyClaimConsistent(aligned).isEmpty(),
        )

        val contradicts = CiFixtures.syntheticRepo(
            "docs-only-contradicts",
            ci = CiFixtures.Templates.ci(okCondition),
            agents = CiFixtures.Templates.agents(
                "Docs-only merges skip all platform builds and do not produce a GitHub Release (path-filtered).",
            ),
        )
        assertTrue(
            "与事实相反的声明必须报错",
            CiGateChecks.docsOnlyClaimConsistent(contradicts).isNotEmpty(),
        )

        // 反真空：删掉那句话不能让检查变绿
        val vacuous = CiFixtures.syntheticRepo(
            "docs-only-vacuous",
            ci = CiFixtures.Templates.ci(okCondition),
            agents = CiFixtures.Templates.agents("Merges to master run the full pipeline."),
        )
        assertTrue(
            "删掉声明必须是失败（反真空守卫），实际：${CiGateChecks.docsOnlyClaimConsistent(vacuous)}",
            CiGateChecks.docsOnlyClaimConsistent(vacuous).isNotEmpty(),
        )
    }

    // ---------- F-A-06：版本自测进 CI ----------

    @Test
    fun `版本自测进 CI 检查能识别 已接入 与 未接入`() {
        val wired = CiFixtures.syntheticRepo(
            "version-selftest",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraGateSteps = CiFixtures.Templates.VERSION_SELFTEST_STEP,
            ),
        )
        assertTrue(
            "接入后应通过，实际：${CiGateChecks.versionSelfTestInCi(wired)}",
            CiGateChecks.versionSelfTestInCi(wired).isEmpty(),
        )
        val missing = CiFixtures.syntheticRepo("version-selftest-missing", ci = CiFixtures.Templates.ci(okCondition))
        assertTrue("未接入时必须报错", CiGateChecks.versionSelfTestInCi(missing).isNotEmpty())
    }

    // ---------- F-V-03：契约新鲜度门禁 ----------

    @Test
    fun `契约新鲜度门禁检查能识别 已加入 与 缺失`() {
        val wired = CiFixtures.syntheticRepo(
            "contract-freshness",
            ci = CiFixtures.Templates.ci(
                okCondition,
                extraGateSteps = CiFixtures.Templates.CONTRACT_FRESHNESS_STEP,
            ),
        )
        assertTrue(
            "加入后应通过，实际：${CiGateChecks.contractFreshnessGate(wired)}",
            CiGateChecks.contractFreshnessGate(wired).isEmpty(),
        )
        val missing = CiFixtures.syntheticRepo("contract-freshness-missing", ci = CiFixtures.Templates.ci(okCondition))
        assertTrue("缺失时必须报错", CiGateChecks.contractFreshnessGate(missing).isNotEmpty())
    }

    // ---------- R5（review §3.6-A1 / §7 Minor 1-3）：发布纯逻辑入口的假绿通道 ----------

    private val releaseEntryScript = "scripts/ci/verify-release-artifacts.sh"
    private val releaseEntryFiles = mapOf(releaseEntryScript to CiFixtures.Templates.releasePureLogicScript())

    /** 真正发版复用同一个入口脚本的 release 步骤（A2 的「合规」一侧）。 */
    private fun releaseVersionJsonStep(scriptPath: String): String =
        "      - name: Normalize assets and generate version.json\n" +
            "        run: |\n" +
            "          bash $scriptPath \"$D{{ needs.resolve-version.outputs.version }}\" " +
            "\"$D{{ needs.resolve-version.outputs.version_code }}\" " +
            "\"$D{{ needs.resolve-version.outputs.release_tag }}\" \"$D{{ github.repository }}\""

    private fun releaseRepo(
        label: String,
        entryJob: String = CiFixtures.Templates.releasePureLogicJob(releaseEntryScript),
        releaseStep: String = releaseVersionJsonStep(releaseEntryScript),
        gateNeeds: String = "resolve-version, release-logic, build-android, build-shell-android",
    ): File = buildRepo(
        label,
        ci = CiFixtures.Templates.ci(
            okCondition,
            releaseNeeds = "resolve-version, gate",
            extraJobs = entryJob,
            gateNeeds = gateNeeds,
            extraConsumedNeed = "release-logic",
            releaseVersionJsonStep = releaseStep,
        ),
        extraFiles = releaseEntryFiles,
    )

    @Test
    fun `入口 step 级 if 与 gate 消费步骤的 continue-on-error 都是必须被判红的假绿通道`() {
        // 合规一侧：入口 step 无 if、gate 消费步骤不吞失败 → A 侧判据必须通过。
        val fixed = releaseRepo("release-entry-clean")
        assertTrue(
            "合规的发布纯逻辑接线不应被判红，实际：${CiGateChecks.releasePureLogicCoveredByGate(fixed)}",
            CiGateChecks.releasePureLogicCoveredByGate(fixed).isEmpty(),
        )
        assertTrue(
            "合规的 gate 必须逐 need 真正消费，实际：${CiGateChecks.gateConsumesRequiredJobs(fixed)}",
            CiGateChecks.gateConsumesRequiredJobs(fixed).isEmpty(),
        )

        // 假绿通道 1（review M14）：入口 step 自身的 `if:` —— PR 上该 step 被跳过，job 仍 success，
        // required `CI Gate` 因此是绿的，而发布纯逻辑在合并前从未执行。
        val stepIf = releaseRepo(
            "release-entry-step-if",
            entryJob = CiFixtures.Templates.releasePureLogicJob(releaseEntryScript).replace(
                "      run: bash $releaseEntryScript",
                "      if: github.ref == 'refs/heads/master'\n      run: bash $releaseEntryScript",
            ),
        )
        val stepIfProblems = CiGateChecks.releasePureLogicCoveredByGate(stepIf)
        assertTrue(
            "入口 step 级 if 必须被判红（PR 上 step 被跳过、job 仍 success），实际：$stepIfProblems",
            stepIfProblems.any { it.contains("step") && it.contains("if") },
        )

        // 假绿通道 2（review M13）：required 汇总作业自己的消费 step 用 continue-on-error 吞掉失败 →
        // 汇总恒绿，整条 required 链路（含发布纯逻辑）失效。
        val swallowing = CiFixtures.syntheticRepo(
            label = "gate-consumption-swallow",
            ci = CiFixtures.Templates.ci(okCondition, releaseNeeds = "resolve-version, gate"),
            extraFiles = releaseEntryFiles,
        )
        CiFixtures.patch(
            swallowing,
            ".github/workflows/ci.yml",
            "      - name: Evaluate required jobs\n        env:",
            "      - name: Evaluate required jobs\n        continue-on-error: true\n        env:",
        )
        val gateProblems = CiGateChecks.gateConsumesRequiredJobs(swallowing)
        assertTrue(
            "gate 消费 step 的 continue-on-error 必须被判红（失败被吞掉、汇总恒绿），实际：$gateProblems",
            gateProblems.any { it.contains("continue-on-error") },
        )
        val chainProblems = CiGateChecks.releasePureLogicCoveredByGate(swallowing)
        assertTrue(
            "required 汇总的消费 step 吞掉失败时，发布纯逻辑链路必须被判红，实际：$chainProblems",
            chainProblems.any { it.contains("continue-on-error") },
        )
    }

    @Test
    fun `真正发版必须复用同一个发布纯逻辑入口 内联第二份生成逻辑不算`() {
        val fixed = releaseRepo("release-reuse-fixed")
        assertTrue(
            "release 复用同一入口时应通过，实际：${CiGateChecks.releasePureLogicCoveredByGate(fixed)}",
            CiGateChecks.releasePureLogicCoveredByGate(fixed).isEmpty(),
        )
        // review M10b：release 改回它自己的内联 jq 副本（两份实现会各自漂移）。
        val inline = releaseRepo(
            "release-reuse-inline",
            releaseStep = "      - name: Normalize assets and generate version.json\n" +
                "        run: |\n" +
                "          jq -n --arg v \"$D{{ needs.resolve-version.outputs.version }}\" " +
                "'{version: ${D}v}' > out/version.json",
        )
        val problems = CiGateChecks.releasePureLogicCoveredByGate(inline)
        assertTrue(
            "release 内联第二份 version.json 生成逻辑必须被判红，实际：$problems",
            problems.any { it.contains("version.json") },
        )
    }

    @Test
    fun `发布纯逻辑入口必须真的消费两个构建产物 needs 与 download path 都要在`() {
        val fixed = releaseRepo("release-artifacts-fixed")
        assertTrue(
            "入口真的下载两个构建产物时应通过，实际：${CiGateChecks.releasePureLogicCoveredByGate(fixed)}",
            CiGateChecks.releasePureLogicCoveredByGate(fixed).isEmpty(),
        )
        // review M11：入口 job 丢掉构建 job 的 needs（产物来路不可核对）。
        val dropsNeeds = releaseRepo(
            "release-artifacts-drops-needs",
            entryJob = CiFixtures.Templates.releasePureLogicJob(releaseEntryScript).replace(
                "  needs: [resolve-version, build-android, build-shell-android]",
                "  needs: [resolve-version]",
            ),
        )
        val needsProblems = CiGateChecks.releasePureLogicCoveredByGate(dropsNeeds)
        assertTrue(
            "入口 job 的 needs 缺少产出该产物的构建 job 时必须判红，实际：$needsProblems",
            needsProblems.any { it.contains("needs") && it.contains("build-android") },
        )
        // review M12：入口 job 删掉 download-artifact 步骤（dl/ 为空 → 无法核对消费的是真实产物）。
        val noDownload = releaseRepo(
            "release-artifacts-no-download",
            entryJob = CiFixtures.Templates.releasePureLogicJob(releaseEntryScript).replace(
                "    - name: Download build artifacts\n      uses: actions/download-artifact@v4\n      with:\n        path: dl\n",
                "",
            ),
        )
        val downloadProblems = CiGateChecks.releasePureLogicCoveredByGate(noDownload)
        assertTrue(
            "入口 job 没有把产物目录下载下来时必须判红，实际：$downloadProblems",
            downloadProblems.any { it.contains("download-artifact") || it.contains("下载") },
        )
    }

    @Test
    fun `入口 job 用 always 加 ref 逃逸必须被判红 而纯 always 与无 if 必须被接受`() {
        // review M5：`always() && github.ref == 'refs/heads/master'` 逃过旧的 contains("always()") 守卫。
        val escaped = releaseRepo(
            "release-entry-always-escape",
            entryJob = CiFixtures.Templates.releasePureLogicJob(releaseEntryScript).replace(
                "  needs: [resolve-version, build-android, build-shell-android]",
                "  if: always() && github.ref == 'refs/heads/master'\n  needs: [resolve-version, build-android, build-shell-android]",
            ),
        )
        val escapedProblems = CiGateChecks.releasePureLogicCoveredByGate(escaped)
        assertTrue(
            "always() 加 ref 限制的入口 job 在 PR 上会 skipped，必须判红，实际：$escapedProblems",
            escapedProblems.any { it.contains("job 级") },
        )
        // 等价合规写法：纯 always() 不排除任何事件 → 必须被接受（不能把合法写法误判成不通过）。
        val alwaysOnly = releaseRepo(
            "release-entry-always-only",
            entryJob = CiFixtures.Templates.releasePureLogicJob(releaseEntryScript).replace(
                "  needs: [resolve-version, build-android, build-shell-android]",
                "  if: always()\n  needs: [resolve-version, build-android, build-shell-android]",
            ),
        )
        assertTrue(
            "纯 always() 不排除任何事件，不应被判红，实际：${CiGateChecks.releasePureLogicCoveredByGate(alwaysOnly)}",
            CiGateChecks.releasePureLogicCoveredByGate(alwaysOnly).isEmpty(),
        )
    }

    // ---------- 契约 README sha256（真实不变量，唯一使用真实文件的地方） ----------

    @Test
    fun `契约 README sha 检查能识别 一致 与 不一致`() {
        val realRoot = RepoLayout.root()
        val dir = CiFixtures.syntheticRepo(
            "contract-hash",
            ci = CiFixtures.Templates.ci(okCondition),
            extraFiles = mapOf(
                "contract/openapi.json" to File(realRoot, "contract/openapi.json").readText(),
                "contract/README.md" to File(realRoot, "contract/README.md").readText(),
            ),
        )
        assertTrue(
            "fixture 的 README sha 应与快照一致（权威产物，不随实现变化）",
            CiGateChecks.contractReadmeHash(dir).isEmpty(),
        )
        CiFixtures.patch(
            dir,
            "contract/README.md",
            Regex("[0-9a-f]{64}").find(File(dir, "contract/README.md").readText())!!.value,
            "0".repeat(64),
        )
        assertTrue(
            "把声明改成错的 sha 后必须报错",
            CiGateChecks.contractReadmeHash(dir).isNotEmpty(),
        )
    }
}
