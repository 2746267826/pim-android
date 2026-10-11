package com.pim.core.ci

import java.io.File

/**
 * F-A-10（PR 描述四段双语章节 + 分支命名）的**语义**判据与执行器。
 *
 * 本轮（round4）**只交付有效 RED 与设计**：真实 `ci.yml` 里还没有这个门禁，
 * 所以对真实仓库调用时会报出「入口不存在」的缺口；
 * 同文件的 `<合规 fixture>` 一侧是绿的，用来证明这些判据既能**接受**合规实现，
 * 也能对每一种坏输入**变红**（不是恒真/恒假的坏执行器）。
 *
 * 设计与接口（下一任务按此实现产品 workflow；设计说明见 round4 交付目录）：
 *
 * 1. **入口位置**：`CI Gate` 的传递依赖里一个不带 `contents: write` 的 job，
 *    或 `CI Gate` 作业自身的一个步骤；其脚本文本必须出现四段标题之一。
 * 2. **输入接口**：入口脚本从环境变量读取**当前事件负载**：
 *    `PR_EVENT_NAME` / `PR_BODY` / `PR_HEAD_REF`；workflow 负责把
 *    `github.event_name` / `github.event.pull_request.body` / `github.event.pull_request.head.ref`
 *    接到这三个变量上。判据**不注入期望值**：它把 workflow 声明的表达式严格解析成探针事件负载
 *    （未声明 / 拼错 / 常量映射都失败），因此既不绑 workflow 的表达式写法，也不允许「接线接错还能过审」。
 * 3. **非 PR 事件**（push master / workflow_dispatch）：没有 PR body 可校验，步骤必须**自行 exit 0**；
 *    job 不得用 job 级 `if:` 把它跳过 —— job 被跳过时 required `CI Gate` 会看到 `skipped` 而非
 *    `success`，master 合并会被自己的门禁挡住。
 * 4. **PR 事件**：四段双语标题任一缺失 → 非 0；head 分支不符合 `task/<n>` 或
 *    `{agent}-{os}/{topic}` → 非 0。
 * 5. **只看当前 PR**：不得查询历史 PR（不调用 `gh` / GitHub API），也不得用
 *    `pull_request_target` 触发器（那会给 fork 代码可写 token）。
 *
 * 判据一律通过**执行真实入口脚本**得出（沿用 `StepRunner` 的 shell 语义），
 * 不检查「脚本里有没有某个字符串」这种可被注释绕过的形状。
 */
object PrMetadataChecks {

    /** AGENTS.md「Pull Request Descriptions Feed The Release Changelog」规定的四段双语标题。 */
    val REQUIRED_HEADINGS = listOf(
        "## 技术修改 / Technical changes",
        "## 功能变化 / Feature changes",
        "## 如何体验 / How to try it",
        "## 测试 / Tests",
    )

    /** 入口脚本约定的输入变量（设计接口，判据据此注入事件负载）。 */
    const val ENV_EVENT_NAME = "PR_EVENT_NAME"
    const val ENV_PR_BODY = "PR_BODY"
    const val ENV_HEAD_REF = "PR_HEAD_REF"

    /** `task/<n>`（工具自动分支）、`{agent}-{os}/{topic}`（agent 分支，os ∈ win/linux），
     *  以及依赖更新工具的分支（`dependabot/…`、`renovate/…`，AGENTS.md 的既有工具例外集合）。 */
    private val ALLOWED_BRANCHES = listOf(
        Regex("^task/[0-9]+$"),
        Regex("^[A-Za-z0-9][A-Za-z0-9._-]*-(?:win|linux)/[A-Za-z0-9][A-Za-z0-9._-]*$"),
        Regex("^(?:dependabot|renovate)/[A-Za-z0-9][A-Za-z0-9._/-]*$"),
    )

    /** 这些标记出现即认为「这段脚本是 PR 元数据校验」（允许实现去读 PR 模板而不是内联标题）。 */
    private val LOCATOR_MARKERS = listOf("技术修改", "如何体验", "pull_request_template")

    /** 当前事件负载的表达式前缀：定位候选与接线判据都要求 env **真的引用**它们（review B1/B5-A1）。 */
    private val EVENT_PAYLOAD_EXPRESSIONS = listOf("github.event_name", "github.event.pull_request.")

    /** 「查询历史 PR」的禁区写法（当前事件负载足够判断，无需访问 GitHub API）。 */
    private val FORBIDDEN_HISTORY_MARKERS = listOf(
        "gh pr list",
        "gh pr view",
        "gh search prs",
        "gh api",
        "api.github.com",
        "pulls?state",
    )

    const val GH_SENTINEL = "GH MUST NOT BE CALLED BY PR METADATA CHECK"

    data class MetadataGate(
        val jobId: String,
        val stepName: String?,
        val script: String,
        val shell: String?,
        val stepEnv: Map<String, String>,
        val jobEnv: Map<String, String>,
        /** step 自身的 `if:`（被跳过时 job 仍报 success，是假绿通道）。 */
        val stepCondition: String? = null,
    ) {
        /** workflow 声明的输入值（step 级优先于 job 级）。 */
        fun declared(key: String): String? = stepEnv[key] ?: jobEnv[key]
    }

    data class Scenario(
        val label: String,
        val eventName: String,
        /** 期望入口的退出码是否为 0。 */
        val expectSuccess: Boolean,
        val body: String,
        val headRef: String,
    ) {
        /** 该场景下某个约定输入变量的期望值。 */
        fun expected(key: String): String = when (key) {
            ENV_EVENT_NAME -> eventName
            ENV_PR_BODY -> body
            ENV_HEAD_REF -> headRef
            else -> error("未知的 PR 元数据输入变量：$key")
        }
    }

    private val SCRIPT_REFERENCE = Regex("(?:scripts|\\.github)/[A-Za-z0-9._/-]+[A-Za-z0-9._-]")

    /** step 的「PR 元数据校验来源」= run 文本 + 它引用的仓库内脚本内容（同 F-A-01 的语义定位）。 */
    fun metadataSource(root: File, run: String): String {
        val referenced = SCRIPT_REFERENCE.findAll(run)
            .map { it.value }
            .filter { File(root, it).isFile }
            .joinToString("\n") { File(root, it).readText() }
        return run + "\n" + referenced
    }

    /** 在 `CI Gate` 的传递依赖（含 gate 自身）里按语义定位 PR 元数据校验入口。 */
    fun findMetadataGate(root: File): MetadataGate? {
        val ci = File(root, ".github/workflows/ci.yml")
        if (!ci.isFile) return null
        val workflow = Workflow.load(ci)
        if (workflow.job("gate") == null) return null
        val consumed = workflow.transitiveNeeds("gate") + "gate"
        for (jobId in consumed) {
            val job = workflow.job(jobId) ?: continue
            if (job.canWriteContents()) continue
            val candidates = job.steps.filter { candidate ->
                candidate.run?.let { run ->
                    val src = metadataSource(root, run)
                    LOCATOR_MARKERS.any { src.contains(it) }
                } == true
            }
            if (candidates.isEmpty()) continue
            // review B5：优先选「env 真的引用当前事件负载」的候选 —— 否则仓库里另一个只提到标题标记的
            // step（或纯 `echo` 的假候选）会把合规实现误判成不通过 / 把假候选当成合规入口。
            val step = candidates.firstOrNull { declaresEventPayload(job.env, it.env) } ?: candidates.first()
            return MetadataGate(jobId, step.name, step.run!!, step.shell, step.env, job.env, step.condition)
        }
        return null
    }

    private fun declaresEventPayload(jobEnv: Map<String, String>, stepEnv: Map<String, String>): Boolean =
        (jobEnv.values + stepEnv.values).any { value ->
            EVENT_PAYLOAD_EXPRESSIONS.any { value.contains(it) }
        }

    /** 接线类判据：入口存在、无写权限、被 required gate 消费、不跳 job、只读当前事件、无历史 PR 查询。 */
    fun wiringProblems(root: File): List<String> {
        val ci = File(root, ".github/workflows/ci.yml")
        if (!ci.isFile) return listOf("找不到 .github/workflows/ci.yml（无法核对 PR 元数据门禁）")
        val workflow = Workflow.load(ci)
        val problems = mutableListOf<String>()

        if (workflow.triggers.keys.any { it == "pull_request_target" }) {
            problems += "ci.yml 使用了 `pull_request_target` 触发器：该事件会带着可写 token 运行 fork 的代码，" +
                "PR 元数据校验必须用 `pull_request`（F-A-10 设计约束）。"
        }

        // review B2：PR body 改坏后旧 SHA 上的 success 不能继续算最新 check。
        problems += pullRequestTriggerProblems(root)

        val gate = findMetadataGate(root)
                ?: return problems + listOf(
                    "RED（F-A-10）：`CI Gate` 的传递依赖里找不到 PR 元数据校验入口。" +
                        "审计验收要求「PR 描述缺章节时 CI 变红」，但当前只有 PR 模板、没有任何检查，" +
                        "模板不是门禁。设计要求：一个无 `contents: write` 的 job（或 `CI Gate` 自身的步骤）" +
                        "读取当前事件负载的 PR body / head 分支，四段双语标题缺一或分支命名不合规即非 0，" +
                        "push 等非 PR 事件由步骤自行成功退出（不得用 job 级 if 跳过）。",
                )

        val job = workflow.job(gate.jobId)
        if (job != null && gate.jobId != "gate") {
            val condition = job.condition
            if (condition != null && CiGateChecks.conditionExcludesAnyTrigger(condition)) {
                problems += "PR 元数据校验 job `${gate.jobId}` 带 job 级 `if:`（$condition）：" +
                    "被跳过时 required `CI Gate` 看到的是 `skipped`，master 合并会被自己的门禁挡住。" +
                    "push 上没有 PR body 可校验，应由**步骤自行 exit 0**，而不是让 job 跳过。"
            }
        }

        // R5（review M14 的 B 侧等价物）：step 级 `if:` 同样是假绿通道 ——
        // step 被跳过时 job 仍报 success，required `CI Gate` 依旧是绿的。
        if (gate.stepCondition != null) {
            problems += "PR 元数据校验入口 step「${gate.stepName ?: "<unnamed>"}」带 step 级 `if:`" +
                "（${gate.stepCondition}）：条件不成立时该 step 被跳过、job 仍报 success，" +
                "`CI Gate` 依旧是绿的。非 PR 事件必须由脚本自行 exit 0，而不是跳过 step。"
        }

        // R5（review §4.3-B1）：三个输入必须由 workflow 自己声明，且声明值严格解析为当前事件负载。
        problems += inputWiringProblems(root, gate)

        if (!CiGateChecks.consumedByRequiredGate(workflow, gate.jobId)) {
            problems += "PR 元数据校验 job `${gate.jobId}` 没有被 required `CI Gate` 真正消费：" +
                "只把 job 挂进 needs 而不读它的 result 时，缺章节的 PR 仍然能通过 required check（F-A-10）。"
        }

        val src = metadataSource(root, gate.script)
        val forbidden = FORBIDDEN_HISTORY_MARKERS.filter { src.contains(it) }
        if (forbidden.isNotEmpty()) {
            problems += "PR 元数据校验不得查询历史 PR（命中 ${forbidden}）：" +
                "只追当前事件负载即可，历史 PR 不追溯（F-A-10 设计约束）。"
        }
        return problems
    }

    /**
     * R5（review B2）：`pull_request` 必须显式包含 `edited`。
     *
     * GitHub 的 `pull_request` 默认只跑 `opened / synchronize / reopened`；PR 先绿、随后把 body 改坏时，
     * 旧 SHA 上那条 success 仍是最新 check，required 检查不会重跑 —— 门禁形同可以事后绕过。
     */
    fun pullRequestTriggerProblems(root: File): List<String> {
        val ci = File(root, ".github/workflows/ci.yml")
        if (!ci.isFile) return emptyList()
        val workflow = Workflow.load(ci)
        if (!workflow.triggers.containsKey("pull_request")) {
            return listOf("ci.yml 没有 `pull_request` 触发器：PR 元数据门禁不会在 PR 上运行（F-A-10）。")
        }
        val types = triggerTypes(workflow.triggers["pull_request"])
        val required = listOf("opened", "synchronize", "reopened", "edited")
        val missing = required - types.toSet()
        if (missing.isEmpty()) return emptyList()
        val actual = if (types.isEmpty()) "<默认 opened/synchronize/reopened>" else types.toString()
        return listOf(
            "`pull_request` 的 activity types 缺少 $missing（实际 = $actual）：只改 PR 描述不会重跑门禁，" +
                "旧 SHA 上那条 success 仍是最新 check（review B2）。" +
                "修法：显式声明 `types: [opened, synchronize, reopened, edited]`。",
        )
    }

    private fun triggerTypes(trigger: Any?): List<String> = when (trigger) {
        is List<*> -> trigger.mapNotNull { it?.toString() }
        is Map<*, *> -> Workflow.asStringList(trigger["types"])
        else -> emptyList()
    }

    /**
     * R5（review §4.3-B1）：`PR_EVENT_NAME` / `PR_BODY` / `PR_HEAD_REF` 必须由 workflow 自己声明，
     * 且声明值要**严格解析**成当前事件负载：未声明、拼错（未登记表达式）、常量映射都必须失败。
     *
     * 判据用两组**不同**的探针事件负载求值：常量映射无法同时等于两组期望值；拼错表达式会被
     * [ExpressionSubstitution.substitute] 直接拒绝；未声明则连变量都不存在。判据本身**不再**把期望值
     * 覆盖进子进程 env，因此「接线接错也能过审」这条假绿通道被堵上。
     */
    fun inputWiringProblems(root: File, gate: MetadataGate): List<String> {
        val problems = mutableListOf<String>()
        for (key in listOf(ENV_EVENT_NAME, ENV_PR_BODY, ENV_HEAD_REF)) {
            val declared = gate.declared(key)
            if (declared == null) {
                problems += "PR 元数据门禁 workflow 没有声明输入变量 `$key`：入口只能读到空值，" +
                    "四段标题/分支校验会退化成恒真或恒假。接线必须来自 `github.event*`（不接受判据注入期望值）。"
                continue
            }
            wiringProblem(root, key, declared)?.let { problems += it }
        }
        return problems
    }

    /** 单个输入变量的接线问题（两组探针都通过才算合规）；返回 null 表示该变量接线正确。 */
    private fun wiringProblem(root: File, key: String, declared: String): String? {
        for (probe in WIRING_PROBES) {
            val want = probe.expected(key)
            val resolved = runCatching {
                ExpressionSubstitution.substitute(declared, expressionValues(root, probe))
            }.getOrElse { failure ->
                return "PR 元数据门禁的 `$key` 声明值 `$declared` 无法解析为当前事件负载：" +
                    "表达式拼错或未登记（${failure.message}）。接线必须引用 `github.event*`。"
            }
            if (resolved != want) {
                return "PR 元数据门禁的 `$key` 声明值 `$declared` 解析为 `${resolved.take(80)}`，" +
                    "与探针「${probe.label}」的期望值不一致：接线接错或接成了常量。"
            }
        }
        return null
    }

    /** 接线判据用的两组探针事件负载（body 与 head ref 都不同，常量映射无法同时满足）。 */
    private val WIRING_PROBES: List<Scenario> = listOf(
        Scenario(
            label = "wiring probe A",
            eventName = "pull_request",
            expectSuccess = true,
            body = fullBody(),
            headRef = "deepseek-linux/audit-remediation",
        ),
        Scenario(
            label = "wiring probe B",
            eventName = "pull_request",
            expectSuccess = true,
            body = bodyMissing(REQUIRED_HEADINGS.first()),
            headRef = "task/7",
        ),
    )

    /** 行为类判据：用当前事件负载执行真实入口脚本，逐场景核对退出码。 */
    fun behaviourProblems(root: File): List<String> {
        val gate = findMetadataGate(root)
            ?: return listOf("RED（F-A-10）：找不到 PR 元数据校验入口，无法核对四段标题/分支/push 三态行为。")
        val problems = mutableListOf<String>()
        for (scenario in scenarios()) {
            val result = try {
                runScenario(root, gate, scenario)
            } catch (failure: Exception) {
                problems += "执行 PR 元数据校验入口时出错（${scenario.label}）：${failure.message}"
                continue
            }
            val ok = if (scenario.expectSuccess) result.exitCode == 0 else result.exitCode != 0
            if (!ok) {
                problems += "场景「${scenario.label}」期望" +
                    (if (scenario.expectSuccess) "入口 0（接受）" else "入口非 0（拒绝）") +
                    "，实际 exit=${result.exitCode}。\n${result.render()}"
            }
            if (result.stdout.contains(GH_SENTINEL) || result.stderr.contains(GH_SENTINEL)) {
                problems += "场景「${scenario.label}」调用了 gh：PR 元数据校验必须只看当前事件负载，" +
                    "不查询历史 PR（这也让检查在没有 token 的 fork PR 上不可用）。\n${result.render()}"
            }
        }
        return problems
    }

    fun allProblems(root: File): List<String> = wiringProblems(root) + behaviourProblems(root)

    /** 判据覆盖的场景（缺任一标题 / 分支不合规 / 两个允许分支 / push 无 body）。 */
    fun scenarios(): List<Scenario> {
        val full = fullBody()
        val list = mutableListOf(
            Scenario("push master：没有 PR body，步骤必须自行成功", "push", expectSuccess = true, body = "", headRef = "master"),
            Scenario("workflow_dispatch：没有 PR body，步骤必须自行成功", "workflow_dispatch", expectSuccess = true, body = "", headRef = ""),
        )
        for (heading in REQUIRED_HEADINGS) {
            list += Scenario(
                label = "PR 缺章节「$heading」",
                eventName = "pull_request",
                expectSuccess = false,
                body = full.replace(heading, ""),
                headRef = "deepseek-linux/audit-remediation",
            )
        }
        list += Scenario("PR body 完全为空", "pull_request", expectSuccess = false, body = "", headRef = "xiaoi-linux/x")
        list += Scenario(
            label = "分支命名不合规（fix-release-unbound-tag，历史 PR #4 的实际分支）",
            eventName = "pull_request",
            expectSuccess = false,
            body = full,
            headRef = "fix-release-unbound-tag",
        )
        // review B3：标题只作为内联引用出现（真实 heading 行被替换掉）也必须被拒 —— 子串匹配会放过它。
        list += Scenario(
            label = "「## 测试 / Tests」只以内联引用出现（不是真实 heading 行）",
            eventName = "pull_request",
            expectSuccess = false,
            body = full.replace(REQUIRED_HEADINGS.last(), "见 `${REQUIRED_HEADINGS.last()}` 章节"),
            headRef = "deepseek-linux/audit-remediation",
        )
        for (ref in listOf(
            "task/42",
            "xiaoi-linux/ci-release-serialization",
            "deepseek-linux/audit-remediation",
            "antigravity-win/location-fix",
            // AGENTS.md 的既有工具例外集合：依赖更新工具自动开的分支不受 {agent}-{os}/{topic} 约束。
            "dependabot/gradle/android",
            "renovate/lockfile-maintenance",
        )) {
            list += Scenario("允许的分支 $ref", "pull_request", expectSuccess = true, body = full, headRef = ref)
        }
        return list
    }

    /** 四段标题齐全、内容非空的合规 PR 描述。 */
    fun fullBody(): String = REQUIRED_HEADINGS.joinToString("\n\n") { "$it\n\n- 内容" } + "\n"

    /** 只缺 [heading] 一段的描述（其余三段齐全）。 */
    fun bodyMissing(heading: String): String = fullBody().replace(heading, "")

    fun branchAllowed(headRef: String): Boolean = ALLOWED_BRANCHES.any { it.matches(headRef) }

    /**
     * 在临时沙箱里执行入口脚本：拷贝仓库 `scripts/`（支持实现把逻辑放脚本里），
     * PATH 前置一个「一旦被调用就失败并打印 [GH_SENTINEL]」的假 `gh`。
     *
     * R5（review §4.3-B1）：三个约定输入变量**不再**由判据用期望值覆盖，而是把 workflow 声明的表达式
     * 严格解析成当前事件负载（见 [resolveDeclaredInput]）：未声明 / 拼错 / 常量映射都会在这里直接失败，
     * 因此「接线接错也能过审」的假绿通道被堵上。判据可注入的只有本场景**事件负载**的表达式取值，
     * 这与真实 Actions 上 workflow 自己接线的语义一致。
     */
    fun runScenario(root: File, gate: MetadataGate, scenario: Scenario): StepRunner.Result {
        val dir = File(System.getProperty("java.io.tmpdir"), "pim-pr-meta-${System.nanoTime()}")
        dir.deleteRecursively()
        File(dir, "bin").mkdirs()
        File(File(dir, "bin"), "gh").apply {
            writeText("#!/usr/bin/env bash\necho '$GH_SENTINEL' >&2\nexit 1\n")
            setExecutable(true)
        }
        File(root, "scripts").takeIf { it.isDirectory }?.copyRecursively(File(dir, "scripts"), overwrite = true)

        val values = expressionValues(root, scenario)
        val script = ExpressionSubstitution.substitute(gate.script, values)
        val expected = mapOf(
            ENV_EVENT_NAME to scenario.eventName,
            ENV_PR_BODY to scenario.body,
            ENV_HEAD_REF to scenario.headRef,
        )
        val env = gate.jobEnv.filterKeys { it !in expected.keys }.mapValues { (_, v) -> substituteOrKeep(v, values) } +
            gate.stepEnv.filterKeys { it !in expected.keys }.mapValues { (_, v) -> substituteOrKeep(v, values) } +
            expected.mapValues { (key, want) -> resolveDeclaredInput(gate, key, want, values) } +
            mapOf("PATH" to "${File(dir, "bin").absolutePath}:${System.getenv("PATH")}")
        return try {
            StepRunner.run(
                script = script,
                workDir = dir,
                env = env,
                bashArgs = StepRunner.bashArgsFor(gate.shell),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * R5（review §4.3-B1）：严格解析 workflow 自己声明的输入值。
     *
     * - 未声明变量 → 失败（不能由判据凭空注入期望值）；
     * - 声明值里的表达式拼错 / 未登记 → [ExpressionSubstitution.substitute] 直接抛错；
     * - 解析结果必须等于当前事件负载的期望值（常量映射与错引用在这里被拒绝）。
     */
    private fun resolveDeclaredInput(
        gate: MetadataGate,
        key: String,
        want: String,
        values: Map<String, String>,
    ): String {
        val declared = gate.declared(key)
            ?: error("PR 元数据门禁 workflow 没有声明输入变量 `$key`：判据不接受用期望值直接注入来绕过接线（B1）。")
        val resolved = ExpressionSubstitution.substitute(declared, values)
        check(resolved == want) {
            "PR 元数据门禁的 `$key` 声明值 `$declared` 解析为 `${resolved.take(80)}`，" +
                "与当前事件负载的期望值不一致：接线接错或接成了常量（B1）。"
        }
        return resolved
    }

    private fun substituteOrKeep(value: String, values: Map<String, String>): String =
        runCatching { ExpressionSubstitution.substitute(value, values) }.getOrDefault(value)

    /**
     * 判据执行入口时可用的 workflow 表达式取值。现实写法都应命中；
     * 出现未登记表达式时由 [ExpressionSubstitution.substitute] 直接报错（不静默留空）。
     */
    fun expressionValues(root: File, scenario: Scenario): Map<String, String> = buildMap {
        putAll(ExpressionSubstitution.resolveVersionOutputs())
        putAll(ExpressionSubstitution.reusableWorkflowValues())
        put("github.event_name", scenario.eventName)
        put("github.event.pull_request.body", scenario.body)
        put("github.event.pull_request.head.ref", scenario.headRef)
        put("github.event.pull_request.base.ref", "master")
        put("github.event.pull_request.number", "1")
        put("github.event.number", "1")
        put("github.head_ref", scenario.headRef)
        put("github.base_ref", "master")
        put("github.ref", "refs/heads/master")
        put("github.repository", "2746267826/pim-android")
        val ci = File(root, ".github/workflows/ci.yml")
        if (ci.isFile) {
            Workflow.load(ci).jobs.forEach { put("needs.${it.id}.result", "success") }
        }
    }
}
