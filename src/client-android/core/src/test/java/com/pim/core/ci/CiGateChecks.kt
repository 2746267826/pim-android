package com.pim.core.ci

import java.io.File
import java.security.MessageDigest

/**
 * CI / 发布门禁检查器：纯函数、以仓库根目录为参数，便于用「变异副本」做故障对照
 * （证明检查器是**会失败**的，而不是恰好恒真）。
 *
 * 判据一律按**语义**写，不绑具体脚本名 / step 名 / 字面量写法：
 * - release 的 `needs` 条件 → [ReleaseDecision] 真实四态求值（I-2）；
 * - 发布纯逻辑入口 → 在 gate 依赖闭包里语义查找「执行 version.json 生成/校验且无写权限」的 job（I-3/D-3）；
 * - 签名标志传递 → 从 `with:` 值里发现由 `is_release` 推导的 boolean 输入名，再验证它在两个可复用
 *   workflow 的签名相关 step 上生效（C-3/D-1）。
 */
object CiGateChecks {

    val WORKFLOWS = listOf(
        ".github/workflows/ci.yml",
        ".github/workflows/build-android.yml",
        ".github/workflows/build-shell-android.yml",
    )

    /** 构建工作流的单元测试命令必须覆盖该 Gradle 工程里所有含 src/test 的模块（F-A-02 / REQ-AND-01）。 */
    fun unitTestCoverage(root: File): List<String> {
        val problems = mutableListOf<String>()
        for (relative in WORKFLOWS) {
            val file = File(root, relative)
            if (!file.isFile) continue
            val workflow = Workflow.load(file)
            val invocations = GradleInvocations.from(workflow)
            invocations.groupBy { it.projectRoot }.forEach { (projectRoot, group) ->
                val required = modulesWithUnitTests(root, projectRoot)
                if (required.isEmpty()) {
                    // M-C：working-directory 打错 / 探测不到工程根时必须报错，不能静默跳过
                    // （否则「命令写在哪」变成不可判定的假绿通道）。
                    problems += "$relative 的 gradlew 调用（${group.flatMap { it.tasks }}）无法定位 Gradle 工程根：" +
                        "working-directory=`$projectRoot` 下探测不到任何含 src/test 的模块" +
                        "（缺少 settings.gradle.kts 或模块声明），无法核对单元测试覆盖。"
                    return@forEach
                }
                val covered = group.flatMap { it.tasks }.mapNotNull(::moduleOfTestTask).toSet()
                val missing = (required - covered).sorted()
                if (missing.isNotEmpty()) {
                    problems += "$relative 在 $projectRoot 下没有执行这些模块的单元测试：$missing" +
                        "（已覆盖 $covered，实际命令 ${group.flatMap { it.tasks }}）"
                }
            }
        }
        return problems
    }

    /**
     * F-A-06（已授权补充整改）：版本自测脚本必须进 CI，且要**真正被 required `CI Gate` 消费**。
     *
     * 三条独立要求：
     * 1. 脚本被某个 job 执行；
     * 2. 执行它的 job 不得带 `contents: write`（PR 上无发布权限即可跑）；
     * 3. 该 job 必须被 required 汇总作业消费 —— 要么它就是汇总作业本身，要么它在汇总作业的
     *    `needs` 里且汇总脚本**真的读取**它的 result（只挂 `needs` 不算，RED-2）。
     */
    fun versionSelfTestInCi(root: File): List<String> {
        val file = File(root, ".github/workflows/ci.yml")
        if (!file.isFile) return emptyList()
        val workflow = Workflow.load(file)
        val runners = workflow.jobs.filter { job ->
            job.runScripts().any { it.contains("scripts/ci/test-resolve-version.sh") }
        }
        if (runners.isEmpty()) {
            return listOf(
                "scripts/ci/test-resolve-version.sh 存在但不被任何 workflow 执行（F-A-06）——" +
                    "版本号逻辑与 action.yml 输出键一致性从未在 CI 里跑过",
            )
        }
        val problems = mutableListOf<String>()
        for (job in runners) {
            if (job.canWriteContents()) {
                problems += "执行版本自测的 job `${job.name ?: job.id}` 带 contents: write：" +
                    "该检查必须在无发布权限下也能跑（F-A-06）。"
            }
        }
        val gate = requiredAggregator(workflow)
        if (gate != null) {
            val covered = runners.any { job ->
                job.id == gate.id || (job.id in gate.needs && job.id in gate.consumedNeeds())
            }
            if (!covered) {
                problems += "版本自测虽然被 CI 执行，但没有被 required `${gate.name ?: gate.id}` 真正消费：" +
                    "汇总脚本没有读取执行它的 job 的 result，失败不会让 required check 变红（F-A-06 / RED-2）。"
            }
        }
        return problems
    }

    /** required 汇总作业：优先按 `name: CI Gate`（= 实际 required 的 check 名）定位，否则按语义。 */
    fun requiredAggregator(workflow: Workflow): WorkflowJob? =
        workflow.jobs.firstOrNull { it.name == "CI Gate" }
            ?: workflow.jobs.firstOrNull { it.isRequiredAllAggregator() }

    /**
     * 发布纯逻辑校验的**语义**定位（I-3 / D-3，不绑脚本文件名）：
     * CI Gate 的传递依赖（不含带 `contents: write` 的 release 作业本身）里，
     * 存在一个 job，其某个 step 的 `run` 真正生成或校验 `version.json`，且该 job 无写权限。
     */
    data class PureLogicValidator(
        val jobId: String,
        val stepName: String?,
        val script: String,
        val shell: String?,
        /** 该 step 是否用 `continue-on-error` 吞掉失败（禁止的假绿写法）。 */
        val continueOnError: Boolean = false,
        /**
         * 入口 step 自身的 `if:`（R5 / review M14）：step 被跳过时 job 仍报 success，
         * required `CI Gate` 因此是绿的，而发布纯逻辑在合并前**从未执行**。
         */
        val condition: String? = null,
    )

    fun findReleasePureLogicValidator(root: File): PureLogicValidator? {
        val ci = File(root, ".github/workflows/ci.yml")
        if (!ci.isFile) return null
        val workflow = Workflow.load(ci)
        if (workflow.job("gate") == null) return null
        val consumed = workflow.transitiveNeeds("gate") + "gate"
        for (jobId in consumed) {
            val job = workflow.job(jobId) ?: continue
            if (job.canWriteContents()) continue
            val step = job.steps.firstOrNull { step ->
                step.run?.let { releasePureLogicSource(root, it).contains("version.json") } == true
            } ?: continue
            return PureLogicValidator(jobId, step.name, step.run!!, step.shell, step.continueOnError, step.condition)
        }
        return null
    }

    private val SCRIPT_REFERENCE = Regex("(?:scripts|\\.github)/[A-Za-z0-9._/-]+[A-Za-z0-9._-]")

    /**
     * step 的「发布纯逻辑来源」= run 文本 + 它引用的仓库内脚本内容。
     * 这样判据既不绑脚本文件名，也不要求把 version.json 逻辑内联在 run 里。
     */
    fun releasePureLogicSource(root: File, run: String): String {
        val referenced = SCRIPT_REFERENCE.findAll(run)
            .map { it.value }
            .filter { File(root, it).isFile }
            .joinToString("\n") { File(root, it).readText() }
        return run + "\n" + referenced
    }

    fun releasePureLogicCoveredByGate(root: File): List<String> {
        val ci = File(root, ".github/workflows/ci.yml")
        if (!ci.isFile) return emptyList()
        val workflow = Workflow.load(ci)
        val gate = requiredAggregator(workflow)
            ?: return listOf("ci.yml 里找不到 required 汇总作业 `CI Gate`")
        val problems = mutableListOf<String>()
        val label = gate.name ?: gate.id

        // R5（review M13）：required 汇总作业**自己的消费步骤**用 continue-on-error 吞掉失败时，
        // 汇总恒绿，整条 required 链路（含发布纯逻辑）失效。旧的守卫只看入口 step，看不到这里。
        val swallowingGateSteps = gateConsumptionSteps(gate).filter { it.continueOnError }
        if (swallowingGateSteps.isNotEmpty()) {
            problems += "required 汇总 `$label` 的消费步骤 " +
                swallowingGateSteps.map { it.name ?: "<unnamed>" } +
                " 声明了 `continue-on-error`：失败被吞掉、job 仍报 success，`$label` 因此恒绿，" +
                "发布纯逻辑与其他 need 的结果都不再能拦住合并（禁止用 continue-on-error 替代失败传播）。"
        }

        val validator = findReleasePureLogicValidator(root)
        if (validator == null) {
            problems += "发布纯逻辑（资产命名 + version.json + 异常场景）在合并前没有执行路径：" +
                "要求 CI Gate 的传递依赖里存在一个「执行 version.json 生成/校验」且不带 " +
                "contents: write 的 job（脚本名不限，按 step 的 run 语义识别）。当前 gate 依赖 = ${gate.needs}，" +
                "release 作业在 PR 上恒被跳过（审计 F-A-01）。"
        } else {
            if (validator.continueOnError) {
                // 假绿通道：step 失败但 job 仍 success，required check 照样绿。禁止用它替代真实失败传播。
                problems += "发布纯逻辑入口 step「${validator.stepName ?: "<unnamed>"}」声明了 `continue-on-error`：" +
                    "入口失败会被吞掉、job 仍报 success，CI Gate 因此不会变红（审计禁止用 continue-on-error 替代失败传播）。"
            }
            // R5（review M14）：入口 step 自身的 `if:` 同样是假绿通道 —— step 被跳过，job 仍 success。
            if (validator.condition != null) {
                problems += "发布纯逻辑入口 step「${validator.stepName ?: "<unnamed>"}」带 step 级 `if:`" +
                    "（${validator.condition}）：条件不成立时该 step 被跳过、job 仍报 success，" +
                    "PR 上的 `$label` 依旧是绿的，发布纯逻辑在合并前从未执行。事件分流必须写在脚本里（自行 exit 0/非 0）。"
            }
            // 入口必须真的在 PR 事件上跑：job 级 `if:` 把它限制到别的触发条件时，PR 上拿到的只是 skipped，
            // 合并前就没有执行路径（要求正是「在 PR 上真正运行」）。按真实表达式对三种触发两态求值，
            // 因此 `always() && github.ref == …` 这种逃逸也会被拒绝（review M5）。
            if (validator.jobId != gate.id) {
                val entryJob = workflow.job(validator.jobId)
                val entryCondition = entryJob?.condition
                if (entryCondition != null && conditionExcludesAnyTrigger(entryCondition)) {
                    problems += "发布纯逻辑入口 job `${validator.jobId}` 带 job 级 `if:`（$entryCondition）：" +
                        "被条件排除的事件上它只会变成 skipped，发布纯逻辑在合并前就没有真正执行（I-3）。" +
                        "需要它只在特定事件跑时应由脚本自行判断并以非 0/0 表达，而不是让 job 跳过。"
                }
                if (entryJob != null) {
                    problems += buildArtifactConsumptionProblems(
                        root,
                        workflow,
                        entryJob,
                        releasePureLogicSource(root, validator.script),
                    )
                }
            }
            problems += releaseEntryReuseProblems(root, workflow, validator)
        }
        // RED-2 ②：纯逻辑入口必须位于 release 的**必经路径**上，不能只挂在 gate.needs。
        val release = workflow.job("release")
        if (release == null) {
            problems += "ci.yml 里找不到 release 作业（无法核对发布必经路径）"
        } else {
            val requiredForRelease = release.needs.contains(gate.id) ||
                (validator != null && release.needs.contains(validator.jobId))
            if (!requiredForRelease) {
                problems += "发布作业 release 不经过 required 汇总 `${gate.name ?: gate.id}`（needs = ${release.needs}）：" +
                    "挂在 gate.needs 里的失败即使让 `CI Gate` 变红，release 也仍会运行（RED-2）。" +
                    "修法：让 release 依赖 gate（或在 needs 里显式包含纯逻辑入口 job）。"
            }
        }
        return problems
    }

    /**
     * R5 / review M5：入口 job 的 `if:` 是否会在 PR / push / workflow_dispatch 任一触发上把入口排除掉。
     *
     * 旧实现用 `condition.contains("always()")` 放行，于是
     * `always() && github.ref == 'refs/heads/master'` 逃逸 —— 它在 PR 上把 job 变成 skipped。
     * 这里对真实表达式逐触发求值：只有「所有触发都为真」（无条件、或 `always()` 这类不排除任何事件的写法）才放行；
     * 无法求值的表达式一律判不通过（fail-closed）。
     */
    fun conditionExcludesAnyTrigger(condition: String): Boolean {
        for (state in TRIGGER_STATES) {
            val runs = runCatching {
                ConditionExpression.truthy(ConditionExpression.evaluate(condition, state))
            }.getOrElse { return true }
            if (!runs) return true
        }
        return false
    }

    /** 三种触发下 `github.*` 的取值（用于 [conditionExcludesAnyTrigger] 的两态/三态求值）。 */
    private val TRIGGER_STATES: List<Map<String, Any?>> = listOf(
        mapOf(
            "github.event_name" to "pull_request",
            "github.ref" to "refs/pull/1/merge",
            "github.ref_name" to "1/merge",
            "github.head_ref" to "deepseek-linux/audit-remediation",
            "github.base_ref" to "master",
        ),
        mapOf("github.event_name" to "push", "github.ref" to "refs/heads/master", "github.ref_name" to "master"),
        mapOf("github.event_name" to "workflow_dispatch", "github.ref" to "refs/heads/master", "github.ref_name" to "master"),
    )

    /** 入口脚本里的 `*.apk` 产物 glob，例如 `dl/pim-android-<slug>/x.apk` 形态：根 `dl`、artifact 名前缀 `pim-android-`。 */
    data class ArtifactGlob(val root: String, val namePrefix: String, val raw: String)

    private val APK_GLOB = Regex("[A-Za-z0-9._][A-Za-z0-9._/-]*\\*[A-Za-z0-9._/*-]*\\.apk")

    fun artifactGlobs(source: String): List<ArtifactGlob> =
        APK_GLOB.findAll(source).map { match ->
            val prefixPath = match.value.substringBefore('*')
            ArtifactGlob(
                root = prefixPath.substringBeforeLast('/', ""),
                namePrefix = prefixPath.substringAfterLast('/'),
                raw = match.value,
            )
        }.distinct().toList()

    /**
     * R5 / review M11 + M12（A3）：入口必须真的消费**两个构建产物**，否则「从真实构建产物生成」只是声明。
     *
     * 1. 入口 job 里要有 `actions/download-artifact`，且 `path` 指向入口脚本 glob 的根（缺了它 `dl/` 为空）；
     * 2. 入口 job 的 `needs` 要覆盖「上传该 artifact 前缀的构建 job」（缺了它产物来路不可核对）。
     *
     * 判据从**入口脚本的 glob**推出产物根与前缀，所以不绑 job 名与目录名。
     */
    private fun buildArtifactConsumptionProblems(
        root: File,
        workflow: Workflow,
        entry: WorkflowJob,
        source: String,
    ): List<String> {
        val globs = artifactGlobs(source)
        if (globs.isEmpty()) {
            return listOf(
                "发布纯逻辑入口 job `${entry.id}` 的脚本里找不到 `*.apk` 产物 glob：" +
                    "无法核对它消费的是真实构建产物（A3）。",
            )
        }
        val problems = mutableListOf<String>()
        val downloads = entry.steps.filter { it.uses?.contains("download-artifact") == true }
        for (glob in globs) {
            val pathMatches = downloads.any { normalizePath(it.withInputs["path"].orEmpty()) == normalizePath(glob.root) }
            if (!pathMatches) {
                problems += "发布纯逻辑入口 job `${entry.id}` 没有把产物目录 `${glob.root}` 下载下来：" +
                    "要求入口 job 里有 actions/download-artifact 且 `path` 指向入口脚本 glob 的根" +
                    "（实际 download 步骤 = ${downloads.map { it.name ?: it.uses.orEmpty() }}）。"
            }
            val producers = artifactProducers(root, workflow, glob.namePrefix)
            if (producers.isEmpty()) {
                problems += "找不到上传 `${glob.namePrefix}*` 产物的构建 job（入口 `${entry.id}`）：" +
                    "入口脚本消费的产物必须由仓库里真实的构建 job 上传，否则「从真实构建产物生成」不可核对。"
            } else {
                val missing = (producers - entry.needs.toSet()).sorted()
                if (missing.isNotEmpty()) {
                    problems += "发布纯逻辑入口 job `${entry.id}` 的 needs 缺少产出该产物的构建 job $missing" +
                        "（needs = ${entry.needs}）：删掉 needs 后入口拿不到真实产物却仍被判据放行（A3）。"
                }
            }
        }
        return problems
    }

    /** ci.yml 里「上传了 artifact 名以 [namePrefix] 开头」的 job id（本地 step 或经由可复用 workflow）。 */
    private fun artifactProducers(root: File, ci: Workflow, namePrefix: String): Set<String> {
        val uploaderWorkflows = mutableSetOf<String>()
        File(root, ".github/workflows").listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (!file.isFile || !(file.name.endsWith(".yml") || file.name.endsWith(".yaml"))) return@forEach
            val workflow = runCatching { Workflow.load(file) }.getOrNull() ?: return@forEach
            if (workflow.jobs.any { job -> job.steps.any { it.uploadsArtifactNamed(namePrefix) } }) {
                uploaderWorkflows += "./.github/workflows/${file.name}"
            }
        }
        return ci.jobs.filter { job ->
            job.steps.any { it.uploadsArtifactNamed(namePrefix) } ||
                (job.uses != null && job.uses in uploaderWorkflows)
        }.map { it.id }.toSet()
    }

    private fun WorkflowStep.uploadsArtifactNamed(prefix: String): Boolean =
        uses?.contains("upload-artifact") == true && withInputs["name"].orEmpty().startsWith(prefix)

    /**
     * R5 / review M10b（A2）：真正发版的 version.json 步骤必须复用与 PR 侧**同一个**入口脚本；
     * 内联的第二份生成逻辑不算复用（两份实现会各自漂移，合并前验证过的就不是真正执行的）。
     */
    private fun releaseEntryReuseProblems(
        root: File,
        workflow: Workflow,
        validator: PureLogicValidator,
    ): List<String> {
        val release = workflow.job("release") ?: return emptyList()
        val entryScripts = referencedScripts(root, validator.script)
        if (entryScripts.isEmpty()) return emptyList()
        val releaseStep = release.steps.firstOrNull { step ->
            step.run?.let { releasePureLogicSource(root, it).contains("version.json") } == true
        } ?: return listOf(
            "release 作业里找不到产出/校验 version.json 的步骤：真正发版必须复用与 PR 侧同一个发布纯逻辑入口（A2）。",
        )
        val run = releaseStep.run.orEmpty()
        val releaseScripts = referencedScripts(root, run)
        if (releaseScripts.intersect(entryScripts).isEmpty()) {
            return listOf(
                "release 作业的 version.json 步骤没有复用发布纯逻辑入口脚本 $entryScripts" +
                    "（它引用的仓库脚本 = $releaseScripts，空 = 内联了第二份实现）：" +
                    "两份实现会各自漂移，PR 上验证过的与真正执行的就不是同一份逻辑（A2）。",
            )
        }
        if (run.contains("jq") && run.contains("version.json")) {
            return listOf(
                "release 作业的 version.json 步骤在复用入口脚本之外还内联了 jq 生成逻辑：" +
                    "内联的第二份实现不算复用同一个入口（A2）。",
            )
        }
        return emptyList()
    }

    /** step 的 run 文本里引用的、**仓库内真实存在**的脚本路径。 */
    fun referencedScripts(root: File, run: String): Set<String> =
        SCRIPT_REFERENCE.findAll(run).map { it.value }.filter { File(root, it).isFile }.toSet()

    private fun normalizePath(path: String): String = path.trim().removePrefix("./").trimEnd('/')

    /** 在该 workflow 里，[jobId] 是否被 required 汇总作业真正消费（自身即汇总作业也算）。 */
    fun consumedByRequiredGate(workflow: Workflow, jobId: String): Boolean {
        val gate = requiredAggregator(workflow) ?: return false
        if (jobId == gate.id) return true
        return jobId in gate.needs && jobId in gate.consumedNeeds()
    }

    /**
     * RED-2：required check `CI Gate` 必须**逐 need 真正消费**它声明依赖的每个 job。
     *
     * 判据不是「脚本里出现了 job 名」（那会被 `check "x" "$UNUSED"` 之类绕过），而是按 GitHub 语义
     * 把结果代入 gate 步骤的 `env` / `run` 后**真跑真实脚本**：逐个 need 注入
     * failure/cancelled/skipped，gate 必须非 0；全部 success 时必须为 0（反向守卫）。
     * 只把 job 挂进 `needs` 而不读该 result 的实现会被抓出来。
     *
     * 接受等价写法：`needs.<job>.result`（逐 job 显式）或 `needs.*.result`（通用安全遍历）。
     */
    fun gateConsumesRequiredJobs(root: File): List<String> {
        val ciFile = File(root, ".github/workflows/ci.yml")
        if (!ciFile.isFile) return emptyList()
        val workflow = Workflow.load(ciFile)
        val gate = requiredAggregator(workflow)
            ?: return listOf("ci.yml 里找不到 required 汇总作业 `CI Gate`（无法核对它是否逐个消费 need）")
        val label = gate.name ?: gate.id
        if (gate.needs.isEmpty()) return listOf("`$label` 没有声明任何 needs")
        val problems = mutableListOf<String>()
        val dir = File(System.getProperty("java.io.tmpdir"), "pim-gate-consume-${System.nanoTime()}")
        try {
            val allSteps = gateConsumptionSteps(gate)
            // R5（review M13）：带 `continue-on-error` 的消费步骤**不构成消费** ——
            // 它失败时被吞掉、job 仍报 success，required check 依旧绿。单独报出来，并从探针里剔除。
            val swallowing = allSteps.filter { it.continueOnError }
            if (swallowing.isNotEmpty()) {
                problems += "`$label` 的消费步骤 ${swallowing.map { it.name ?: "<unnamed>" }} 声明了 `continue-on-error`：" +
                    "这些步骤失败会被吞掉、job 仍报 success，逐 need 消费形同不存在（RED-2 假绿通道）。"
            }
            val probes = allSteps.filterNot { it.continueOnError }
            if (probes.isEmpty()) {
                return problems + (
                    "`$label` 没有可执行的「消费 need 结果」步骤：脚本与 env 都没有引用 needs.<job>.result —— " +
                        "列在 needs 里不等于被消费（RED-2）。"
                    )
            }
            for (need in gate.needs) {
                for (bad in ReleaseDecision.BAD_RESULTS) {
                    val results = gate.needs.associateWith { if (it == need) bad else "success" }
                    val outcome = runGateSteps(gate, results, File(dir, "$need-$bad"), probes)
                    val failed = outcome.any { it.exitCode != 0 }
                    if (!failed) {
                        problems += "依赖 `${label}` 的 need `$need` 为 $bad 时汇总作业仍然成功退出：" +
                            "脚本没有真的消费 `needs.$need.result`（挂 needs 不读结果），" +
                            "PR 的 required check 因此仍是绿的。\n${outcome.joinToString("\n") { it.render() }}"
                    }
                }
            }
            if (gate.needs.size > 1) {
                val allSuccess = gate.needs.associateWith { "success" }
                val outcome = runGateSteps(gate, allSuccess, File(dir, "all-success"), probes)
                if (outcome.any { it.exitCode != 0 } || outcome.isEmpty()) {
                    problems += "反向守卫失败：所有 need 均 success 时 `$label` 仍未成功退出" +
                        "（恒假/过度严格的汇总会把「不可发布」伪装成合规）。"
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        return problems
    }

    /** 汇总作业里真正读取 need 结果的步骤（脚本或 env 出现 `needs.` 引用）。 */
    private fun gateConsumptionSteps(gate: WorkflowJob): List<WorkflowStep> =
        gate.steps.filter { step ->
            (step.run.orEmpty() + "\n" + step.env.values.joinToString("\n")).contains("needs.")
        }

    /** 把结果代入后真实执行汇总步骤；任一步非 0 即停止（等价于 job 的步骤语义）。 */
    private fun runGateSteps(
        gate: WorkflowJob,
        results: Map<String, String>,
        workDir: File,
        steps: List<WorkflowStep> = gateConsumptionSteps(gate),
    ): List<StepRunner.Result> {
        val context = needsContext(gate.needs, results)
        val out = mutableListOf<StepRunner.Result>()
        for (step in steps) {
            val condition = step.condition
            if (condition != null) {
                val runs = runCatching {
                    ConditionExpression.truthy(ConditionExpression.evaluate(condition, context))
                }.getOrDefault(false)
                if (!runs) {
                    out += StepRunner.Result(0, "", "", step.run.orEmpty(), skipped = true)
                    continue
                }
            }
            val script = substituteNeeds(step.run.orEmpty(), results)
            val env = step.env.mapValues { (_, value) -> substituteNeeds(value, results) }
            val result = StepRunner.run(step = step, script = script, workDir = workDir, env = env)
            out += result
            if (result.exitCode != 0) break
        }
        return out
    }

    private val NEEDS_RESULT = Regex("\\$\\{\\{\\s*needs\\.([A-Za-z0-9_-]+)\\.result\\s*\\}\\}")
    private val NEEDS_ALL_RESULT = Regex("\\$\\{\\{\\s*needs\\.\\*\\.result\\s*\\}\\}")

    private fun substituteNeeds(text: String, results: Map<String, String>): String {
        val perJob = NEEDS_RESULT.replace(text) { match ->
            results[match.groupValues[1]] ?: match.value
        }
        return NEEDS_ALL_RESULT.replace(perJob) { results.values.joinToString(" ") }
    }

    /** REQ-COM-04：release 不得在任一必需 job 为 failure/cancelled/skipped 时启动（真实四态语义求值）。 */
    fun releaseBlocksBadNeeds(root: File): List<String> {
        val file = File(root, ".github/workflows/ci.yml")
        if (!file.isFile) return emptyList()
        return ReleaseDecision.problems(Workflow.load(file))
    }

    /**
     * F-A-04 第②条路径（C-1）：验签步骤的失败必须在**该 step 声明的 shell 语义**下传播。
     * 接受两种等价实现：`shell: bash`（GitHub 内建 shell = `bash -eo pipefail`），
     * 或脚本内 `set -o pipefail`。
     */
    fun apksignerFailurePropagates(root: File): List<String> {
        val problems = mutableListOf<String>()
        for (relative in listOf(".github/workflows/build-android.yml", ".github/workflows/build-shell-android.yml")) {
            val file = File(root, relative)
            if (!file.isFile) continue
            val workflow = Workflow.load(file)
            val verifySteps = workflow.jobs.flatMap { job ->
                job.steps.filter { it.run?.contains("apksigner") == true }.map { job.id to it }
            }
            if (verifySteps.isEmpty()) {
                problems += "$relative 里找不到 apksigner 验签步骤（无法核对失败传播链路）"
                continue
            }
            for ((jobId, step) in verifySteps) {
                val script = step.run.orEmpty()
                val ok = step.shell?.trim().equals("bash", ignoreCase = true) ||
                    Regex("(?m)^\\s*set\\s+-[a-z]*o\\s+pipefail").containsMatchIn(script)
                if (!ok) {
                    problems += "$relative 的 $jobId/${step.name} 验签失败不会传播为步骤失败：" +
                        "step 未声明 `shell: bash`，脚本里也没有 `set -o pipefail`，" +
                        "`apksigner verify | tee` 的失败被管道吞掉（F-A-04）。"
                }
            }
        }
        return problems
    }

    /**
     * C-3 / D-1：正式发布标志必须由 ci.yml 的既有 `is_release` 推导，作为 boolean 输入传给
     * **两个**可复用 workflow，并在两个 workflow 的签名相关 step 上生效。
     *
     * 判据按语义发现输入名（不硬编码 `require_signing`/`PIM_IS_RELEASE`），因此任何命名都可通过；
     * 反过来，只改脚本、不改 workflow 接线的「假绿」实现必然被这条判据拦住。
     */
    fun signingFlagWiring(root: File): List<String> {
        val ciFile = File(root, ".github/workflows/ci.yml")
        if (!ciFile.isFile) return emptyList()
        val ci = Workflow.load(ciFile)
        val problems = mutableListOf<String>()
        val buildJobIds = listOf("build-android", "build-shell-android")

        // 1) ci.yml 两个可复用调用都把 is_release 推导的布尔值传下去，且**极性**必须正确。
        val flagByJob = mutableMapOf<String, String>()
        val exprByJob = mutableMapOf<String, String>()
        for (jobId in buildJobIds) {
            val job = ci.job(jobId)
            if (job == null) {
                problems += "ci.yml 里找不到 $jobId 作业"
                continue
            }
            val passed = job.withInputs.filter { (_, value) ->
                value.contains("needs.resolve-version.outputs.is_release")
            }
            when (passed.size) {
                1 -> {
                    val (name, value) = passed.entries.first()
                    flagByJob[jobId] = name
                    exprByJob[jobId] = value
                    // RED-1：对 `with:` 值表达式**两态求值**，要求 require_signing == true ⟺ is_release == 'true'。
                    // `!=`、额外取反、常量、错误引用都会在这里被拒绝（contains 判定检查不出来）。
                    problems += signingFlagPolarityProblems(jobId, name, value)
                }
                0 -> problems += "ci.yml 的 $jobId 没有把 is_release 推导出的签名标志传给可复用 workflow" +
                    "（with = ${job.withInputs}）。正式发布与 fork/普通 PR 因此无法区分（C-3）。"
                else -> problems += "ci.yml 的 $jobId 有多个疑似签名标志输入：${passed.keys}"
            }
        }
        val flagNames = flagByJob.values.toSet()
        if (flagNames.size > 1) {
            problems += "两个构建作业传的签名标志名不一致：$flagByJob（应统一，便于判据与脚本同源）"
        }
        if (exprByJob.size == buildJobIds.size && exprByJob.values.map { normalizeExpression(it) }.distinct().size > 1) {
            problems += "两个构建作业的签名标志表达式不一致：$exprByJob（同一语义应两处一致，避免一处极性被改坏）"
        }

        // 2) 每个可复用 workflow：workflow_call 声明 boolean 必填；workflow_dispatch 声明 boolean 默认 false；
        //    签名相关 step 上必须引用该 input。
        for ((jobId, file) in buildJobIds.map { it to ".github/workflows/$it.yml" }) {
            val workflowFile = File(root, file)
            if (!workflowFile.isFile) {
                problems += "找不到可复用 workflow：$file"
                continue
            }
            val workflow = Workflow.load(workflowFile)
            val flag = flagByJob[jobId]
            if (flag == null) {
                problems += "$file 无法核对签名标志：ci.yml 没有把 is_release 推导值传给它"
                continue
            }
            val callInput = workflow.workflowCallInputs[flag]
            if (callInput == null) {
                problems += "$file 的 workflow_call 没有声明输入 `$flag`（签名步骤无法知道本次是否正式发布）"
            } else if (callInput.type != "boolean" || !callInput.required) {
                problems += "$file 的 workflow_call 输入 `$flag` 应为 required boolean，实际 type=${callInput.type} required=${callInput.required}"
            }
            val dispatchInput = workflow.workflowDispatchInputs[flag]
            if (dispatchInput == null) {
                problems += "$file 的 workflow_dispatch 没有声明 `$flag`（手动触发时无法显式选择非发布，默认会误当发布）"
            } else if (dispatchInput.type != "boolean") {
                problems += "$file 的 workflow_dispatch 输入 `$flag` 应为 boolean，实际 type=${dispatchInput.type}"
            } else if (dispatchInput.default != "false") {
                problems += "$file 的 workflow_dispatch 输入 `$flag` 默认应为 false（手动触发默认不发版），实际 ${dispatchInput.default}"
            }

            val signing = workflow.jobs.flatMap { job ->
                job.signingSteps().map { step -> Triple(job, step, job.env) }
            }
            if (signing.isEmpty()) {
                problems += "$file 识别不到签名相关 step（无法核对发布标志是否生效）"
            }
            for ((job, step, _) in signing) {
                val envKeys = step.env.filterValues { it.contains("inputs.$flag") }.keys
                val jobEnvKeys = job.env.filterValues { it.contains("inputs.$flag") }.keys
                val byCondition = step.condition?.contains("inputs.$flag") == true ||
                    job.condition?.contains("inputs.$flag") == true
                if (envKeys.isEmpty() && jobEnvKeys.isEmpty() && !byCondition) {
                    problems += "$file 的 ${job.id}/${step.name} 未引用签名标志 `inputs.$flag`：" +
                        "非发布构建（fork/普通 PR）无法跳过正式发布才需要的签名要求，或发布构建不会 fail-closed（C-3/C-5）。"
                    continue
                }
                // M-F：把标志挂进 env ≠ 脚本真的使用它。要求脚本确实读取该变量（或按 if 条件化）。
                if (!byCondition) {
                    val script = step.run.orEmpty()
                    val used = (envKeys + jobEnvKeys).any { key ->
                        Regex("\\$\\{?${Regex.escape(key)}\\b").containsMatchIn(script)
                    }
                    if (!used) {
                        problems += "$file 的 ${job.id}/${step.name} 把签名标志挂进了 env（${envKeys + jobEnvKeys}），" +
                            "但脚本从未读取该变量：引用 ≠ 使用，非发布/发布不会被真正区分（M-F）。"
                    }
                }
            }
        }
        return problems
    }

    /** F-A-05：文档里引用的仓库内路径必须真实存在。 */
    fun docReferences(root: File): List<String> {
        val docs = listOf(
            "AGENTS.md",
            ".github/pull_request_template.md",
            "contract/README.md",
            "README.md",
        ).filter { File(root, it).isFile }
        if (docs.isEmpty()) return listOf("没有找到任何待检查的文档")

        val reference = Regex(
            "(?:^|[\\s`'\"(（])((?:scripts|docs|contract)/[A-Za-z0-9._/-]*[A-Za-z0-9._-]+" +
                "|\\.github/(?:workflows|actions)/[A-Za-z0-9._/-]*[A-Za-z0-9._-]+)",
        )
        val missing = linkedSetOf<String>()
        for (doc in docs) {
            File(root, doc).readLines().forEachIndexed { index, line ->
                reference.findAll(line).forEach { match ->
                    val candidate = match.groupValues[1].trimEnd('.', ',', ';', ':')
                    if (!File(root, candidate).exists()) {
                        missing += "$doc:${index + 1} -> $candidate"
                    }
                }
            }
        }
        return missing.toList()
    }

    /**
     * F-A-05 / I-6：docs-only 的行为声明必须**存在**（反真空守卫）且与 `ci.yml` 的真实触发条件一致。
     *
     * 判据（确定性，不做自然语言理解）：
     * - AGENTS.md 必须至少有一行同时提到 `docs-only` 与 build/Release 后果，否则视为「没有可判定的声明」；
     * - 该行的过滤极性必须等于 ci.yml 实际是否有 paths 过滤：
     *   出现 `no path filter` / `not path-filtered` / `regardless` / `still` / `including docs-only` / `always`
     *   视为「不过滤」声明；只出现 `path-filtered` / `skip` / `do not` / `will not` 等视为「过滤」声明；
     * - 极性无法判定时判失败，而不是静默通过。
     *
     * 采纳 D-5 的「改文档」路线：不为迎合旧句子给 `pull_request` 加 paths 过滤。
     */
    fun docsOnlyClaimConsistent(root: File): List<String> {
        val agentsFile = File(root, "AGENTS.md")
        val ciFile = File(root, ".github/workflows/ci.yml")
        if (!agentsFile.isFile || !ciFile.isFile) return emptyList()
        val declaredFiltered = Workflow.load(ciFile).triggerHasPathFilter()
        val lines = agentsFile.readLines()
            .mapIndexed { index, line -> index + 1 to line }
            .filter { (_, line) -> line.contains("docs-only", ignoreCase = true) }

        if (lines.isEmpty()) {
            return listOf(
                "AGENTS.md 没有任何关于 docs-only 合并行为的声明（F-A-05 反真空守卫）：" +
                    "删掉那句话不能让文档与 CI 触发条件一致，必须给出与实际一致的说明。",
            )
        }
        // 只在「提到 docs-only 的那个子句」里判极性，避免同句尾部的 "still expected for accurate history" 干扰。
        val claims = lines.mapNotNull { (lineNo, line) ->
            val clause = line.split(Regex("[.;]"))
                .map { it.trim() }
                .filter { it.contains("docs-only", ignoreCase = true) }
                .firstOrNull { it.contains("build", true) || it.contains("release", true) || it.contains("发版") }
            clause?.let { lineNo to it }
        }
        if (claims.isEmpty()) {
            return listOf(
                "AGENTS.md 提到 docs-only 但没有说明对构建/发版的影响（F-A-05）：" +
                    "行 ${lines.map { it.first }} 无法判定与 ci.yml 触发条件是否一致。",
            )
        }
        val unfiltered = Regex("(?i)(no path filter|not path-filtered|regardless|including docs-only|always)")
        // M-A：`still` 只在「仍然运行/构建/产出」语境里表示不过滤；否则 `still skip` 这类矛盾句会被误判为不过滤。
        val stillRuns = Regex("(?i)still\\s+(run|runs|build|builds|produce|produces|trigger|triggers|release|releases)")
        val filtered = Regex("(?i)(path-filtered|\\bskips?\\b|\\bdo not\\b|\\bwill not\\b|\\bwon't\\b|不触发|跳过)")
        val problems = mutableListOf<String>()
        for ((lineNo, clause) in claims) {
            val claimsUnfiltered = unfiltered.containsMatchIn(clause) || stillRuns.containsMatchIn(clause)
            val claimsFiltered = filtered.containsMatchIn(clause)
            when {
                // M-A：两类关键词同时命中 ⇒ 不可判定，必须报错而不是「先命中哪类算哪类」。
                claimsUnfiltered && claimsFiltered -> problems +=
                    "AGENTS.md:$lineNo 的 docs-only 声明同时包含「不过滤」与「过滤/跳过」两类关键词，" +
                        "无法判定真实极性（自相矛盾的句子不能被算作合规）：$clause"
                claimsUnfiltered && declaredFiltered -> problems +=
                    "AGENTS.md:$lineNo 声称 docs-only 不走 path 过滤（仍会构建/可能发版），" +
                        "但 ci.yml 实际有 paths 过滤"
                claimsFiltered && !declaredFiltered -> problems +=
                    "AGENTS.md:$lineNo 声称 docs-only 合并会跳过构建/不产生 Release（path-filtered），" +
                        "但 ci.yml 的触发条件没有任何 paths/paths-ignore 过滤（F-A-05）"
                !claimsUnfiltered && !claimsFiltered -> problems +=
                    "AGENTS.md:$lineNo 的 docs-only 声明没有可判定的过滤极性（无法判定一致性）：$clause"
            }
        }
        return problems
    }

    /**
     * F-V-03：CI 必须有「刷新契约快照后 git diff --quiet」的新鲜度门禁，且要**真正被 required `CI Gate` 消费**、
     * 在无写权限的 job 里运行（PR 上就能跑）。只挂 `needs` 不算（RED-2）。
     */
    fun contractFreshnessGate(root: File): List<String> {
        val file = File(root, ".github/workflows/ci.yml")
        if (!file.isFile) return emptyList()
        val workflow = Workflow.load(file)
        val jobs = workflow.jobs.filter { job ->
            job.steps.any { step ->
                val script = step.run ?: return@any false
                script.contains("fetch-contract.sh") &&
                    script.contains("contract/openapi.json") &&
                    script.contains("git diff")
            }
        }
        if (jobs.isEmpty()) {
            return listOf(
                "没有契约新鲜度门禁（F-V-03/F-V-04）：CI 里找不到「刷新契约后 " +
                    "git diff --quiet -- contract/openapi.json」的步骤，快照落后不会变红。",
            )
        }
        val problems = mutableListOf<String>()
        for (job in jobs) {
            if (job.canWriteContents()) {
                problems += "契约新鲜度门禁位于带 contents: write 的 job `${job.name ?: job.id}`：要求无发布权限即可执行。"
            }
            if (!consumedByRequiredGate(workflow, job.id)) {
                problems += "契约新鲜度门禁的 job `${job.name ?: job.id}` 没有被 required `CI Gate` 真正消费：" +
                    "快照落后导致的失败不会让 required check 变红（RED-2）。"
            }
        }
        return problems
    }

    /** 契约快照与其 README 声明的 sha256 必须一致（防「刷新了快照却没更新说明」）。 */
    fun contractReadmeHash(root: File): List<String> {
        val readmeFile = File(root, "contract/README.md")
        val snapshot = File(root, "contract/openapi.json")
        if (!readmeFile.isFile || !snapshot.isFile) return emptyList()
        val declared = Regex("[0-9a-f]{64}").find(readmeFile.readText())?.value
            ?: return listOf("contract/README.md 没有声明 sha256")
        val actual = MessageDigest.getInstance("SHA-256").digest(snapshot.readBytes())
            .joinToString("") { "%02x".format(it) }
        return if (declared == actual) {
            emptyList()
        } else {
            listOf("contract/README.md 声明的 sha256=$declared 与实际快照 $actual 不一致")
        }
    }

    // ---------- helpers ----------

    /**
     * RED-1：对 `with:` 里的签名标志表达式做**两态求值**，要求
     * `require_signing == true ⟺ needs.resolve-version.outputs.is_release == 'true'`。
     *
     * 只做 `contains` 判定的写法无法区分 `== 'true'` 与 `!= 'true'`；这里逐态求值：
     * - `!= 'true'`：is_release=true 得到 false（应 true）⇒ 报错；
     * - 额外取反 / 常量：某一态必然与期望相反 ⇒ 报错；
     * - 错引用（引用了未提供的属性链）：解析为 null/抛错 ⇒ 报错。
     */
    fun signingFlagPolarityProblems(jobId: String, inputName: String, rawValue: String): List<String> {
        val expr = singleExpression(rawValue)
            ?: return listOf(
                "ci.yml 的 $jobId 传给 `$inputName` 的值不是单一的 \${{ }} 表达式，无法证明它由 is_release 正极性推导：$rawValue",
            )
        val observed = mutableListOf<String>()
        for (state in listOf("true", "false")) {
            val context = mapOf<String, Any?>("needs.resolve-version.outputs.is_release" to state)
            val got = runCatching { ConditionExpression.truthy(ConditionExpression.evaluate(expr, context)) }
                .getOrElse { failure ->
                    return listOf(
                        "ci.yml 的 $jobId 签名标志表达式无法在 is_release=$state 下求值" +
                            "（${failure.message}）：$expr —— 引用必须能由 is_release 判定。",
                    )
                }
            val want = state == "true"
            observed += "is_release=$state ⇒ $got（应 $want）"
            if (got != want) {
                return listOf(
                    "ci.yml 的 $jobId 签名标志**极性错误**：${observed.joinToString("；")}。" +
                        "正式发布（is_release=true）必须得到 true，fork/普通 PR（is_release=false）必须得到 false；" +
                        "`!=`、额外取反、常量、错引用都会在这里被拒绝。表达式：$expr",
                )
            }
        }
        return emptyList()
    }

    private fun singleExpression(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("\${{") || !trimmed.endsWith("}}")) return null
        val inner = trimmed.removePrefix("\${{").removeSuffix("}}").trim()
        return inner.ifBlank { null }
    }

    private fun normalizeExpression(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    fun modulesWithUnitTests(root: File, projectRoot: String): Set<String> {
        val settings = File(root, "$projectRoot/settings.gradle.kts")
        if (!settings.isFile) return emptySet()
        return Regex("include\\(\"(:[^\"]+)\"\\)")
            .findAll(settings.readText())
            .map { it.groupValues[1].trimStart(':').replace(':', '/') }
            .filter { File(root, "$projectRoot/$it/src/test").isDirectory }
            .toSet()
    }

    fun moduleOfTestTask(task: String): String? {
        val parts = task.split(':').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        if (!parts.last().startsWith("test")) return null
        return parts.dropLast(1).joinToString("/")
    }
}
