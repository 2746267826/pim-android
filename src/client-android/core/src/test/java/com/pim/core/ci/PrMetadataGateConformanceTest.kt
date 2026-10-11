package com.pim.core.ci

import com.pim.core.contract.RepoLayout
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-A-10（PR 描述四段双语章节 + 分支命名）**本轮只交付有效 RED 与设计**：
 * 真实 `ci.yml` 里还没有这个门禁，因此下面两条「真实仓库」用例是**设计内 RED**；
 * fixture 一侧（`合规实现 / 每种坏输入的变异`）现在就是绿的，用来证明判据是**会失败**的，
 * 而不是恒真/恒假的坏执行器 —— 这也是这两条 RED 属于「目标行为尚未实现」而非「判据自身坏了」的依据。
 *
 * 设计接口与逐条判据见 [PrMetadataChecks] 的 KDoc；产品 workflow 留待下一切片（先过独立 review）。
 * 分母不动：F-A-10 仍是 26 条里的观察项，本文件不把它改写成已修复。
 */
class PrMetadataGateConformanceTest {

    private val root = RepoLayout.root()

    private val okCondition =
        "always() && needs.resolve-version.outputs.is_release == 'true' && " +
            "!contains(needs.*.result, 'failure') && !contains(needs.*.result, 'cancelled') && " +
            "!contains(needs.*.result, 'skipped')"

    private val scriptPath = "scripts/ci/verify-pr-metadata.sh"
    private val gateJobId = "pr-metadata"

    // ---------------------------------------------------------------- 真实仓库：设计内 RED

    @Test
    fun `CI Gate 必须消费一个无写权限的 PR 元数据校验 job`() {
        val problems = PrMetadataChecks.wiringProblems(root)
        assertTrue(
            "F-A-10 设计内 RED：审计验收要求「PR 描述缺章节时 CI 变红」，但 ci.yml 里只有 PR 模板、" +
                "没有任何检查——模板不是门禁。修法（下一切片，需先独立 review）：\n" +
                "  · 无 contents: write 的 job（或 CI Gate 自身的步骤）读取当前事件负载的 PR body / head 分支；\n" +
                "  · 四段双语标题缺一或分支命名不合规 → 非 0；\n" +
                "  · 非 PR 事件（push master 等）由步骤自行成功退出，不得用 job 级 if 跳过；\n" +
                "  · 不用 pull_request_target，不查询历史 PR。\n当前缺口：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `四段双语章节缺任一 或 分支不合规 都必须让真实 CI Gate 变红`() {
        val problems = PrMetadataChecks.behaviourProblems(root)
        assertTrue(
            "F-A-10 设计内 RED：找不到可执行的 PR 元数据校验入口，无法核对「缺章节/分支错误 → gate 变红」。\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- fixture：判据自洽性（现在即绿）

    /** 合规 fixture：入口在无写权限 job 里、被 gate 逐 need 消费、只看当前事件负载。 */
    private fun fixture(label: String): File = CiFixtures.syntheticRepo(
        label = label,
        ci = CiFixtures.Templates.ci(
            releaseCondition = okCondition,
            releaseNeeds = "resolve-version, gate",
            extraJobs = CiFixtures.Templates.prMetadataJob(scriptPath, jobId = gateJobId),
            gateNeeds = "resolve-version, build-android, build-shell-android, $gateJobId",
            extraConsumedNeed = gateJobId,
        ),
        extraFiles = mapOf(scriptPath to CiFixtures.Templates.prMetadataScript()),
    )

    @Test
    fun `合规 fixture 的 PR 元数据入口必须被接受 且被 required gate 真正消费`() {
        val dir = fixture("pr-metadata-fixed")
        try {
            val gate = PrMetadataChecks.findMetadataGate(dir)
            assertNotNull(
                "合规 fixture 的入口必须被语义定位到；wiring：${PrMetadataChecks.wiringProblems(dir)}",
                gate,
            )
            assertTrue("入口应定位到 fixture 的 job", gate!!.jobId == gateJobId)
            assertTrue(
                "合规 fixture 不应有接线问题：${PrMetadataChecks.wiringProblems(dir)}",
                PrMetadataChecks.wiringProblems(dir).isEmpty(),
            )
            assertTrue(
                "合规 fixture 不应有行为问题：${PrMetadataChecks.behaviourProblems(dir)}",
                PrMetadataChecks.behaviourProblems(dir).isEmpty(),
            )
            // gate 必须逐 need 真跑真实脚本消费它：任一 need 非 success 时 gate 非 0。
            val workflow = Workflow.load(File(dir, ".github/workflows/ci.yml"))
            assertTrue(
                "PR 元数据 job 必须在 required gate 的 needs 里且被消费",
                CiGateChecks.consumedByRequiredGate(workflow, gateJobId),
            )
            assertTrue(
                "gate 未逐 need 消费：${CiGateChecks.gateConsumesRequiredJobs(dir)}",
                CiGateChecks.gateConsumesRequiredJobs(dir).isEmpty(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `四段双语章节缺任一都必须失败 且判据能发现漏检一段的实现`() {
        val dir = fixture("pr-metadata-fixed-two")
        try {
            assertTrue(
                "四段齐全时入口必须成功：${PrMetadataChecks.behaviourProblems(dir)}",
                PrMetadataChecks.behaviourProblems(dir).isEmpty(),
            )
            for (heading in PrMetadataChecks.REQUIRED_HEADINGS) {
                val gate = PrMetadataChecks.findMetadataGate(dir)!!
                val body = PrMetadataChecks.bodyMissing(heading)
                val result = PrMetadataChecks.runScenario(
                    dir,
                    gate,
                    PrMetadataChecks.Scenario("缺 $heading", "pull_request", expectSuccess = false, body = body, headRef = "deepseek-linux/audit-remediation"),
                )
                assertTrue(
                    "缺少「$heading」时入口仍以 0 结束，缺章节的 PR 会通过 required check：\n${result.render()}",
                    result.exitCode != 0,
                )
            }
            // 反向守卫：故意让实现漏检「测试 / Tests」一段，判据必须报出来（不是恒绿）。
            val missingSection = fixture("pr-metadata-miss-one")
            try {
                CiFixtures.patch(missingSection, scriptPath, "## 测试 / Tests\n", "")
                val problems = PrMetadataChecks.behaviourProblems(missingSection)
                assertTrue(
                    "实现漏检「## 测试 / Tests」时判据必须报错，实际：$problems",
                    problems.isNotEmpty(),
                )
                assertTrue(
                    "报错必须指向漏检的那一段：$problems",
                    problems.any { it.contains("## 测试 / Tests") },
                )
            } finally {
                missingSection.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PR 元数据校验承认 task n 与 agent-os 分支 并拒绝其他命名`() {
        assertTrue("task/42 必须被允许", PrMetadataChecks.branchAllowed("task/42"))
        assertTrue(
            "仓库里实际存在的 agent-os 分支必须被允许",
            listOf(
                "xiaoi-linux/ci-release-serialization",
                "deepseek-linux/audit-remediation",
                "antigravity-win/location-fix",
            ).all { PrMetadataChecks.branchAllowed(it) },
        )
        assertFalse("历史 PR #4 的 fix-release-unbound-tag 不符合命名规则", PrMetadataChecks.branchAllowed("fix-release-unbound-tag"))

        val dir = fixture("pr-metadata-branch")
        try {
            // 合规实现：允许的分支通过、不合规的分支被拒（行为判据已覆盖）。
            assertTrue(PrMetadataChecks.behaviourProblems(dir).isEmpty())
            // 反绿锁：把实现改成只认 task/<n>，判据必须抓出「误拒 agent 分支」。
            val overRestrictive = fixture("pr-metadata-branch-strict")
            try {
                CiFixtures.patch(
                    overRestrictive,
                    scriptPath,
                    "'^(task/[0-9]+|[A-Za-z0-9][A-Za-z0-9._-]*-(win|linux)/[A-Za-z0-9][A-Za-z0-9._-]*|(dependabot|renovate)/[A-Za-z0-9][A-Za-z0-9._/-]*)\$'",
                    "'^task/[0-9]+\$'",
                )
                val problems = PrMetadataChecks.behaviourProblems(overRestrictive)
                assertTrue("只认 task/<n> 的实现必须被判据拒绝，实际：$problems", problems.isNotEmpty())
                assertTrue("必须报出被误拒的 agent 分支：$problems", problems.any { it.contains("xiaoi-linux") })
            } finally {
                overRestrictive.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PR 元数据入口的三个输入必须由 workflow 真实接当前事件负载`() {
        val D = CiFixtures.D
        fun wiring(label: String): File = fixture(label)

        // 合规一侧：三个变量都由 workflow 声明并引用当前事件负载 → 接线判据必须通过。
        val fixed = wiring("pr-metadata-wiring-fixed")
        try {
            assertTrue(
                "合规接线不应被判红，实际：${PrMetadataChecks.wiringProblems(fixed)}",
                PrMetadataChecks.wiringProblems(fixed).isEmpty(),
            )
        } finally {
            fixed.deleteRecursively()
        }

        // 变异 1：表达式拼错（`titel`）—— 旧执行器用期望值覆盖 env，因此完全看不见这种接线错误。
        val typo = fixture("pr-metadata-wiring-typo")
        try {
            CiFixtures.patch(
                typo,
                ".github/workflows/ci.yml",
                "          PR_BODY: $D{{ github.event.pull_request.body }}",
                "          PR_BODY: $D{{ github.event.pull_request.titel }}",
            )
            val problems = PrMetadataChecks.wiringProblems(typo)
            assertTrue("拼错的事件表达式必须被判据报出，实际：$problems", problems.isNotEmpty())
        } finally {
            typo.deleteRecursively()
        }

        // 变异 2：把输入接成常量（不随事件负载变化）。
        val constant = fixture("pr-metadata-wiring-constant")
        try {
            CiFixtures.patch(
                constant,
                ".github/workflows/ci.yml",
                "          PR_HEAD_REF: $D{{ github.event.pull_request.head.ref }}",
                "          PR_HEAD_REF: deepseek-linux/audit-remediation",
            )
            val problems = PrMetadataChecks.wiringProblems(constant)
            assertTrue("常量映射必须被判据报出（不随事件负载变化），实际：$problems", problems.isNotEmpty())
        } finally {
            constant.deleteRecursively()
        }

        // 变异 3：workflow 根本没声明这三个变量（脚本只能读到空值）。
        val undeclared = fixture("pr-metadata-wiring-undeclared")
        try {
            CiFixtures.patch(
                undeclared,
                ".github/workflows/ci.yml",
                "        env:\n" +
                    "          ${PrMetadataChecks.ENV_EVENT_NAME}: $D{{ github.event_name }}\n" +
                    "          ${PrMetadataChecks.ENV_PR_BODY}: $D{{ github.event.pull_request.body }}\n" +
                    "          ${PrMetadataChecks.ENV_HEAD_REF}: $D{{ github.event.pull_request.head.ref }}\n",
                "",
            )
            val problems = PrMetadataChecks.wiringProblems(undeclared)
            assertTrue("未声明事件负载输入必须被判据报出，实际：$problems", problems.isNotEmpty())
        } finally {
            undeclared.deleteRecursively()
        }
    }

    @Test
    fun `只 echo 提到标题的候选不能算 PR 元数据入口`() {
        val echoOnly = CiFixtures.syntheticRepo(
            label = "pr-metadata-echo-only",
            ci = CiFixtures.Templates.ci(
                okCondition,
                releaseNeeds = "resolve-version, gate",
                extraJobs = "pr-metadata:\n" +
                    "  runs-on: ubuntu-latest\n" +
                    "  needs: [resolve-version]\n" +
                    "  permissions:\n" +
                    "    contents: read\n" +
                    "  steps:\n" +
                    "    - run: echo \"## 技术修改 / Technical changes\"",
                gateNeeds = "resolve-version, build-android, build-shell-android, $gateJobId",
                extraConsumedNeed = gateJobId,
            ),
        )
        try {
            val problems = PrMetadataChecks.wiringProblems(echoOnly)
            assertTrue(
                "只 echo 标题（没有把当前事件负载接进来）的候选不能被当成合规入口，实际：$problems",
                problems.isNotEmpty(),
            )
        } finally {
            echoOnly.deleteRecursively()
        }
    }

    @Test
    fun `标题只以内联引用出现时不算真实 heading 行`() {
        val dir = fixture("pr-metadata-inline-heading")
        try {
            val gate = PrMetadataChecks.findMetadataGate(dir)!!
            val inlineBody = PrMetadataChecks.fullBody()
                .replace("## 测试 / Tests", "见 `## 测试 / Tests` 章节")
            assertTrue(
                "判据自身必须确认这份 body 里已经没有独立的标题行",
                inlineBody.lines().none { it.trim() == "## 测试 / Tests" },
            )
            val result = PrMetadataChecks.runScenario(
                dir,
                gate,
                PrMetadataChecks.Scenario(
                    label = "标题只以内联引用出现",
                    eventName = "pull_request",
                    expectSuccess = false,
                    body = inlineBody,
                    headRef = "deepseek-linux/audit-remediation",
                ),
            )
            assertTrue(
                "标题只以内联引用出现时必须非 0（必须是真实 heading 行），实际 exit=${result.exitCode}：\n${result.render()}",
                result.exitCode != 0,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `依赖更新工具的自动分支 dependabot 与 renovate 必须被允许`() {
        assertTrue(
            "dependabot 分支必须被允许（AGENTS.md 既有工具例外集合）",
            PrMetadataChecks.branchAllowed("dependabot/gradle/android"),
        )
        assertTrue(
            "renovate 分支必须被允许（AGENTS.md 既有工具例外集合）",
            PrMetadataChecks.branchAllowed("renovate/lockfile-maintenance"),
        )
        assertTrue(
            "行为场景必须真的覆盖工具自动分支（否则参考实现里的白名单不会被检查）",
            PrMetadataChecks.scenarios().any { it.expectSuccess && it.headRef.startsWith("dependabot/") },
        )

        val dir = fixture("pr-metadata-tooling-branch")
        try {
            val gate = PrMetadataChecks.findMetadataGate(dir)!!
            val result = PrMetadataChecks.runScenario(
                dir,
                gate,
                PrMetadataChecks.Scenario(
                    label = "dependabot 自动分支",
                    eventName = "pull_request",
                    expectSuccess = true,
                    body = PrMetadataChecks.fullBody(),
                    headRef = "dependabot/gradle/android",
                ),
            )
            assertTrue(
                "参考实现必须接受工具自动分支，实际 exit=${result.exitCode}：\n${result.render()}",
                result.exitCode == 0,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pull_request 触发必须包含 edited 以覆盖 PR body 变更`() {
        val fixed = fixture("pr-metadata-trigger-types")
        try {
            assertTrue(
                "合规 fixture 的 pull_request 触发必须含 opened/synchronize/reopened/edited，实际：${PrMetadataChecks.wiringProblems(fixed)}",
                PrMetadataChecks.wiringProblems(fixed).isEmpty(),
            )
            val dropped = fixture("pr-metadata-trigger-types-dropped")
            try {
                CiFixtures.patch(
                    dropped,
                    ".github/workflows/ci.yml",
                    "    types: [opened, synchronize, reopened, edited]",
                    "    types: [opened, synchronize, reopened]",
                )
                val problems = PrMetadataChecks.wiringProblems(dropped)
                assertTrue(
                    "去掉 edited 后必须被判据报出（body 改坏后旧 SHA 上的 success 仍是最新 check），实际：$problems",
                    problems.any { it.contains("edited") },
                )
            } finally {
                dropped.deleteRecursively()
            }
        } finally {
            fixed.deleteRecursively()
        }
    }

    @Test
    fun `push 上没有 PR body 时入口必须自行成功 不得被 job 级 if 跳过`() {
        val dir = fixture("pr-metadata-push")
        try {
            val gate = PrMetadataChecks.findMetadataGate(dir)!!
            for (event in listOf("push", "workflow_dispatch")) {
                val result = PrMetadataChecks.runScenario(
                    dir,
                    gate,
                    PrMetadataChecks.Scenario(event, event, expectSuccess = true, body = "", headRef = ""),
                )
                assertTrue(
                    "$event 上没有 PR body 可校验，入口必须自行以 0 结束（job 跳过会让 required check 看到 skipped）：\n${result.render()}",
                    result.exitCode == 0,
                )
            }
            // 变异 1：让非 PR 事件失败 → 判据必须报出 push 场景不通过。
            val failOnPush = fixture("pr-metadata-push-fail")
            try {
                CiFixtures.patch(
                    failOnPush,
                    scriptPath,
                    "  echo \"非 pull_request 事件（\$EVENT_NAME）：没有 PR 元数据需要校验。\"\n  exit 0",
                    "  echo \"非 pull_request 事件：失败\" >&2\n  exit 1",
                )
                assertTrue(
                    "push 上失败的实现必须被判据报出",
                    PrMetadataChecks.behaviourProblems(failOnPush).isNotEmpty(),
                )
            } finally {
                failOnPush.deleteRecursively()
            }
            // 变异 2：给 job 加 job 级 if，只在 PR 上运行 → 接线判据必须报「不得 job skip」。
            val jobSkip = fixture("pr-metadata-job-skip")
            try {
                CiFixtures.patch(
                    jobSkip,
                    ".github/workflows/ci.yml",
                    "  $gateJobId:\n    runs-on: ubuntu-latest\n",
                    "  $gateJobId:\n    if: github.event_name == 'pull_request'\n    runs-on: ubuntu-latest\n",
                )
                val problems = PrMetadataChecks.wiringProblems(jobSkip)
                assertTrue("job 级 if 跳过必须被判据报出，实际：$problems", problems.isNotEmpty())
                assertTrue(
                    "报错须说明 skipped 会让 required check 变红：$problems",
                    problems.any { it.contains("job 级") && it.contains("skipped") },
                )
            } finally {
                jobSkip.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PR 元数据校验不得查询历史 PR 也不得使用 pull_request_target`() {
        val dir = fixture("pr-metadata-isolation")
        try {
            assertTrue(
                "合规 fixture 不得有禁用写法：${PrMetadataChecks.wiringProblems(dir)}",
                PrMetadataChecks.wiringProblems(dir).isEmpty(),
            )
            // 变异 1：用 gh 查历史 PR → 静态禁止标记 + 运行期假 gh 哨兵都必须报出。
            val ghHistory = fixture("pr-metadata-gh-history")
            try {
                CiFixtures.patch(ghHistory, scriptPath, "fail=0", "fail=0\ngh pr list --state merged >/dev/null || true")
                val problems = PrMetadataChecks.wiringProblems(ghHistory) + PrMetadataChecks.behaviourProblems(ghHistory)
                assertTrue("查询历史 PR 必须被判据报出，实际：$problems", problems.isNotEmpty())
                assertTrue(
                    "必须报出历史 PR 查询：$problems",
                    problems.any { it.contains("历史 PR") } &&
                        problems.any { it.contains("gh pr list") || it.contains(PrMetadataChecks.GH_SENTINEL) },
                )
            } finally {
                ghHistory.deleteRecursively()
            }
            // 变异 2：换成 pull_request_target 触发器。
            val target = fixture("pr-metadata-target")
            try {
                CiFixtures.patch(
                    target,
                    ".github/workflows/ci.yml",
                    "  pull_request:\n    branches: [master]",
                    "  pull_request_target:\n    branches: [master]",
                )
                val problems = PrMetadataChecks.wiringProblems(target)
                assertTrue("pull_request_target 必须被拒绝，实际：$problems", problems.isNotEmpty())
                assertTrue(problems.any { it.contains("pull_request_target") })
            } finally {
                target.deleteRecursively()
            }
            // 变异 3：gate 只挂 needs、脚本不再读该 need 的 result（审计 RED-2 的反面教材）。
            val unwired = fixture("pr-metadata-unwired")
            try {
                CiFixtures.rewriteLines(
                    unwired,
                    ".github/workflows/ci.yml",
                    { it.trim() == "check \"$gateJobId\" \"\$R_EXTRA\"" },
                    "",
                )
                val problems = CiGateChecks.gateConsumesRequiredJobs(unwired)
                assertTrue("只挂 needs 不读 result 的 gate 必须报错，实际：$problems", problems.isNotEmpty())
                assertTrue(
                    "必须报出未被真正消费的 need：$problems",
                    problems.any { it.contains(gateJobId) },
                )
            } finally {
                unwired.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
