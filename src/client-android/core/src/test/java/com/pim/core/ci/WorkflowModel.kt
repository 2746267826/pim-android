package com.pim.core.ci

import com.pim.core.contract.RepoLayout
import java.io.File
import java.util.concurrent.TimeUnit
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * 真实 workflow YAML 的只读视图 + 「把某个 step 的 run 脚本在受控沙箱里真跑一遍」的执行器。
 *
 * 设计意图：CI / 发布门禁的结论不能靠源码 `contains` 断言。凡是有 Running-time 语义的部分
 * （签名决策、`| tee` 管道下的退出码、资产命名与 version.json 生成），都从 workflow 里
 * 抽出**真实脚本**、用受控环境执行、看**真实退出码与输出**。没有运行期语义的部分
 * （job 依赖聚合、触发条件）用真实 YAML 解析后按语义断言。
 */

/** `on.workflow_call.inputs.<name>` / `on.workflow_dispatch.inputs.<name>` 的声明。 */
data class WorkflowInput(
    val name: String,
    val type: String?,
    val required: Boolean,
    val default: String?,
)

data class WorkflowStep(
    val name: String?,
    val run: String?,
    val shell: String?,
    val workingDirectory: String?,
    val env: Map<String, String>,
    val uses: String?,
    val condition: String?,
    /**
     * `continue-on-error: true` 会让**失败被吞掉、job 仍报 success** —— 对 required 门禁来说是
     * 假绿通道（审计明确禁止用它替代真实失败传播）。
     */
    val continueOnError: Boolean = false,
    /** step 的 `with:` 输入（例如 `actions/download-artifact` 的 `path`）。 */
    val withInputs: Map<String, String> = emptyMap(),
) {
    /** 该 step 是否涉及签名 / 验签（用于按语义定位 shell 工作流里的同类步骤）。 */
    fun touchesSigning(): Boolean {
        val script = run ?: return false
        return script.contains("KEYSTORE") || script.contains("apksigner") ||
            script.contains("signing") || script.contains("unsigned") || script.contains("签名")
    }
}

data class WorkflowJob(
    val id: String,
    val name: String?,
    val needs: List<String>,
    val condition: String?,
    val permissions: Map<String, String>,
    val steps: List<WorkflowStep>,
    val defaultsWorkingDirectory: String?,
    val uses: String? = null,
    val withInputs: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
) {
    fun stepByName(name: String): WorkflowStep? = steps.firstOrNull { it.name == name }

    /** 该 job 是否带 `contents: write`（job 未声明 permissions 时已继承 workflow 顶层，M-4）。 */
    fun canWriteContents(): Boolean = permissions["contents"] == "write"

    /** 该 job 任一 step 的 run 脚本里出现的字符串（用于「某 job 是否执行了某段逻辑」的语义判定）。 */
    fun runScripts(): List<String> = steps.mapNotNull { it.run }

    /** 按 run 脚本内容定位 step（不绑 step 名字，允许实现改名/换脚本名）。 */
    fun stepByScript(predicate: (String) -> Boolean): WorkflowStep? =
        steps.firstOrNull { it.run?.let(predicate) == true }

    /** 签名相关 step（按脚本内容判定，app / shell 工作流共用）。 */
    fun signingSteps(): List<WorkflowStep> = steps.filter { it.touchesSigning() }

    /**
     * 该 job 的脚本 / env **真正引用**了哪些 need 的 result（RED-2 / M-G 的逐 need 消费模型）。
     *
     * 「挂在 `needs:` 里」不等于「消费」：必须出现 `needs.<job>.result` 才算消费该 job；
     * 使用 `needs.*.result`（GitHub 的「全部 need 结果集合」）视为安全地通用遍历**所有** need
     * —— 这也是 reviewer 允许的等价写法之一。
     */
    fun consumedNeeds(): Set<String> {
        val text = consumptionText()
        if (text.contains("needs.*.result")) return needs.toSet()
        return needs.filter { text.contains("needs.$it.result") }.toSet()
    }

    /** 判定「是否消费 need」的文本面：job 级 + **step 级** env（结果通常经 step env 传入脚本）+ 脚本。 */
    private fun consumptionText(): String =
        (runScripts() + env.values + steps.flatMap { it.env.values }).joinToString("\n")

    /**
     * 该 job 是否会在给定结果集下执行：`if` 缺省等价于「所有 need 都 success」。
     * 只处理 `always()` 与 `needs.*.result` 子集 —— 见 [ConditionExpression]。
     */
    fun runsUnder(needResults: Map<String, String>, extraContext: Map<String, Any?> = emptyMap()): Boolean {
        if (needs.any { needResults[it] != "success" }) {
            val always = condition?.contains("always()") == true
            if (!always) return false
        }
        val condition = condition ?: return needs.all { needResults[it] == "success" }
        val context = needsContext(needs, needResults) + extraContext
        return ConditionExpression.truthy(ConditionExpression.evaluate(condition, context))
    }
}

/**
 * 「必需 job 全成功才算通过」的聚合 job（本仓 `CI Gate` 的形态）：
 * `if: always()` + 脚本里检查 needs 结果并 `exit 1`。
 * 这是 workflow 表达式之外的**脚本语义**，语义求值时必须显式建模，否则会误判
 * `needs: [gate]` + `needs.gate.result == 'success'` 这种合规等价写法。
 *
 * M-G 修正：不再要求「脚本里出现所有 need 的名字」（all-or-nothing），
 * 只要求它至少**真正消费**了一个 need 的 result；未消费的部分由逐 need 诊断单独报出
 * （见 [WorkflowJob.consumedNeeds] 与 `CiGateChecks.gateConsumesRequiredJobs`）。
 */
fun WorkflowJob.isRequiredAllAggregator(): Boolean {
    if (needs.isEmpty()) return false
    if (condition?.contains("always()") != true) return false
    val text = (runScripts() + env.values + steps.flatMap { it.env.values }).joinToString("\n")
    if (!text.contains("exit 1")) return false
    return consumedNeeds().isNotEmpty()
}

class Workflow private constructor(
    val file: File,
    private val root: Map<String, Any?>,
) {
    val name: String = root["name"] as? String ?: file.name
    // YAML 1.1 下 snakeyaml 会把键 `on:` 解析成 Boolean true，这里两种形式都接受。
    val triggers: Map<String, Any?> = asMap(
        root["on"] ?: root.entries.firstOrNull { (it.key as Any?) == true }?.value,
    )

    /** workflow 顶层 permissions（job 未声明时继承它 —— M-4）。 */
    val topLevelPermissions: Map<String, String> =
        asMap(root["permissions"]).mapValues { (_, v) -> v?.toString() ?: "" }

    val workflowCallInputs: Map<String, WorkflowInput> =
        asMap(asMap(triggers["workflow_call"])["inputs"]).map { (n, raw) -> n to raw.toInput(n) }.toMap()

    val workflowDispatchInputs: Map<String, WorkflowInput> =
        asMap(asMap(triggers["workflow_dispatch"])["inputs"]).map { (n, raw) -> n to raw.toInput(n) }.toMap()

    val jobs: List<WorkflowJob> = asMap(root["jobs"]).map { (id, raw) ->
        val job = asMap(raw)
        val defaults = asMap(job["defaults"])
        WorkflowJob(
            id = id,
            name = job["name"] as? String,
            needs = asStringList(job["needs"]),
            condition = job["if"] as? String,
            permissions = effectivePermissions(job),
            steps = asList(job["steps"]).map { rawStep -> asMap(rawStep).toStep() },
            defaultsWorkingDirectory = asMap(defaults["run"])["working-directory"] as? String,
            uses = job["uses"] as? String,
            withInputs = asMap(job["with"]).mapValues { (_, v) -> v?.toString() ?: "" },
            env = asMap(job["env"]).mapValues { (_, v) -> v?.toString() ?: "" },
        )
    }

    /** job 级 permissions 覆盖顶层；job 未声明则继承顶层（GitHub 语义）。 */
    private fun effectivePermissions(job: Map<String, Any?>): Map<String, String> {
        val declared = asMap(job["permissions"])
        if (declared.isNotEmpty() || job.containsKey("permissions")) {
            return declared.mapValues { (_, v) -> v?.toString() ?: "" }
        }
        return topLevelPermissions
    }

    fun job(id: String): WorkflowJob? = jobs.firstOrNull { it.id == id }

    /** job 的传递依赖闭包（含自身）。 */
    fun transitiveNeeds(jobId: String): Set<String> {
        val seen = linkedSetOf<String>()
        fun visit(id: String) {
            val job = job(id) ?: return
            job.needs.forEach { need -> if (seen.add(need)) visit(need) }
        }
        visit(jobId)
        return seen
    }

    /** 该 workflow 触发条件是否声明了 paths 过滤。 */
    fun triggerHasPathFilter(): Boolean =
        triggers.values.any { trigger ->
            val map = asMap(trigger)
            map.containsKey("paths") || map.containsKey("paths-ignore")
        }

    private fun Any?.toInput(name: String): WorkflowInput = WorkflowInput(
        name = name,
        type = asMap(this)["type"] as? String,
        required = asMap(this)["required"] == true,
        default = asMap(this)["default"]?.toString(),
    )

    private fun Map<String, Any?>.toStep(): WorkflowStep = WorkflowStep(
        name = this["name"] as? String,
        run = this["run"] as? String,
        shell = this["shell"] as? String,
        workingDirectory = this["working-directory"] as? String,
        env = asMap(this["env"]).mapValues { (_, v) -> v?.toString() ?: "" },
        uses = this["uses"] as? String,
        condition = this["if"] as? String,
        continueOnError = this["continue-on-error"] == true,
        withInputs = asMap(this["with"]).mapValues { (_, v) -> v?.toString() ?: "" },
    )

    companion object {
        fun load(file: File): Workflow {
            require(file.isFile) { "workflow 不存在：$file" }
            // SafeConstructor：只解析标准 YAML 标签，不接受任意 Java 类型构造（纵深防御，M-1）。
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val parsed = yaml.load<Any?>(file.reader())
            @Suppress("UNCHECKED_CAST")
            return Workflow(file, parsed as? Map<String, Any?> ?: error("无法解析 $file"))
        }

        fun ci(): Workflow = load(RepoLayout.workflow("ci.yml"))

        fun buildAndroid(): Workflow = load(RepoLayout.workflow("build-android.yml"))

        fun buildShellAndroid(): Workflow = load(RepoLayout.workflow("build-shell-android.yml"))

        @Suppress("UNCHECKED_CAST")
        fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

        fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

        fun asStringList(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is String -> listOf(value)
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> error("无法解析列表：$value")
        }
    }
}

/** Gradle 单元测试调用：项目根目录 + 被调用的 task 列表。 */
data class GradleTestInvocation(val projectRoot: String, val tasks: List<String>)

object GradleInvocations {

    private val GRADLE_CALL = Regex("(?m)^\\s*(?:\\./)?gradlew(?:\\.bat)?\\s+([^\\n&|;]+)")

    fun from(workflow: Workflow): List<GradleTestInvocation> =
        workflow.jobs.flatMap { job ->
            job.steps.mapNotNull { step ->
                val script = step.run ?: return@mapNotNull null
                if (!script.contains("gradlew")) return@mapNotNull null
                val matches = GRADLE_CALL.findAll(script).toList()
                if (matches.isEmpty()) return@mapNotNull null
                val workingDir = step.workingDirectory
                    ?: job.defaultsWorkingDirectory
                    ?: "."
                val projectRoot = normalize(workingDir)
                GradleTestInvocation(
                    projectRoot = projectRoot,
                    tasks = matches.flatMap { it.groupValues[1].split(Regex("\\s+")) }
                        .filter { it.isNotBlank() && !it.startsWith("--") && !it.startsWith("-P") },
                )
            }
        }

    private fun normalize(workingDir: String): String {
        val base = workingDir.removePrefix("\${{ github.workspace }}/").removePrefix("\${{ github.workspace }}")
        val cleaned = base.removePrefix("./").trimEnd('/')
        return if (cleaned.isEmpty() || cleaned == ".") "." else cleaned
    }
}

/**
 * 把 workflow step 的 `run` 脚本在沙箱目录里真跑一遍。
 *
 * **shell 语义按 step 的 `shell:` 求值**（C-1 修复）：
 * - 缺省（`shell` 未声明）→ GitHub 在 Linux 上对 `run` 步骤只做 `bash -e`，**没有 pipefail**；
 * - `shell: bash` → `bash --noprofile --norc -eo pipefail`（GitHub 内建 bash shell 的定义）；
 * - 脚本自身写 `set -o pipefail` 时，执行真实脚本自然会开启 pipefail（等价实现同样被接受）。
 *
 * 不继承本进程环境（不把任何凭据/代理配置带进去），只给最小 PATH/HOME 与调用方指定的变量。
 */
object StepRunner {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val script: String,
        val skipped: Boolean = false,
    ) {
        fun render(): String = buildString {
            appendLine("exit=$exitCode${if (skipped) " (step if=false, skipped)" else ""}")
            appendLine("--- script ---")
            appendLine(script)
            appendLine("--- stdout ---")
            appendLine(stdout)
            appendLine("--- stderr ---")
            appendLine(stderr)
        }
    }

    /**
     * GitHub 对 Linux `run` 步骤的 shell 语义。
     * `bash` 是 GitHub 的内建 shell，命令为 `bash --noprofile --norc -eo pipefail {0}`。
     */
    fun bashArgsFor(shell: String?): List<String> = when {
        shell == null -> listOf("-e")
        shell.trim().equals("bash", ignoreCase = true) -> listOf("--noprofile", "--norc", "-eo", "pipefail")
        shell.trim().equals("sh", ignoreCase = true) -> listOf("-e")
        else -> throw IllegalArgumentException("测试执行器只支持 bash/sh 语义的 shell，实际：$shell")
    }

    fun run(
        script: String,
        workDir: File,
        env: Map<String, String> = emptyMap(),
        bashArgs: List<String> = listOf("-e"),
        timeoutSeconds: Long = 120,
    ): Result {
        workDir.mkdirs()
        val scriptFile = File(workDir, "step.sh")
        scriptFile.writeText(script)
        val githubEnv = File(workDir, "github-env").apply { writeText("") }
        val githubOutput = File(workDir, "github-output").apply { writeText("") }

        val process = ProcessBuilder(listOf("bash") + bashArgs + listOf(scriptFile.absolutePath))
            .directory(workDir)
            .redirectErrorStream(false)
            .apply {
                environment().clear()
                environment()["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
                environment()["HOME"] = workDir.absolutePath
                environment()["LANG"] = "C.UTF-8"
                environment()["GITHUB_ENV"] = githubEnv.absolutePath
                environment()["GITHUB_OUTPUT"] = githubOutput.absolutePath
                environment()["GITHUB_WORKSPACE"] = workDir.absolutePath
                environment().putAll(env)
            }
            .start()

        // 两个流各用一个线程读，避免任一流写满管道缓冲导致子进程阻塞（不是超时挂起）。
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
        val errThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        outThread.start()
        errThread.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            error("step 执行超时（${timeoutSeconds}s）：\n$stdout\n$stderr")
        }
        outThread.join(5000)
        errThread.join(5000)
        return Result(process.exitValue(), stdout.toString(), stderr.toString(), script)
    }

    /** 按 step 声明的 `shell:` 执行真实脚本。 */
    fun run(
        step: WorkflowStep,
        script: String,
        workDir: File,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): Result = run(
        script = script,
        workDir = workDir,
        env = env,
        bashArgs = bashArgsFor(step.shell),
        timeoutSeconds = timeoutSeconds,
    )
}

/** 构造 `needs.<job>.result` / `needs.*.result` 求值上下文。 */
fun needsContext(jobs: Collection<String>, results: Map<String, String>): Map<String, Any?> = buildMap {
    jobs.forEach { job -> put("needs.$job.result", results[job]) }
    put("needs.*.result", jobs.map { results[it] })
}

/**
 * 把 workflow 里的 `${{ ... }}` 表达式替换为 fixture 值；出现未登记表达式直接失败，
 * 避免「表达式没被替换导致脚本以空值跑过」这种假绿。
 */
object ExpressionSubstitution {

    fun substitute(script: String, values: Map<String, String>): String {
        val pattern = Regex("\\$\\{\\{([^}]*)\\}\\}")
        val result = pattern.replace(script) { match ->
            val key = match.groupValues[1].trim()
            values[key] ?: error("未登记的 workflow 表达式：\${{ $key }}")
        }
        check(!result.contains("\${{")) { "仍残留未替换的表达式：$result" }
        return result
    }

    fun resolveVersionOutputs(
        version: String = "26.10.16",
        versionCode: String = "100016",
        artifactSlug: String = "26.10.16",
        releaseTag: String = "android-v26.10.16",
        isRelease: String = "true",
        repository: String = "2746267826/pim-android",
    ): Map<String, String> = mapOf(
        "needs.resolve-version.outputs.version" to version,
        "needs.resolve-version.outputs.version_code" to versionCode,
        "needs.resolve-version.outputs.artifact_slug" to artifactSlug,
        "needs.resolve-version.outputs.release_tag" to releaseTag,
        "needs.resolve-version.outputs.is_release" to isRelease,
        "github.repository" to repository,
    )

    /**
     * 复用工作流调用场景下的表达式值：`inputs.*`、`github.*`、`steps.ver.outputs.*`、`secrets.*`。
     * 未出现的键由 [substitute] 直接报错（防假绿）。
     */
    fun reusableWorkflowValues(
        signingFlagName: String = "require_signing",
        signingFlag: Boolean = true,
        artifactSlug: String = "26.10.16",
        version: String = "26.10.16",
        versionCode: String = "100016",
    ): Map<String, String> = mapOf(
        "inputs.version" to version,
        "inputs.version_code" to versionCode,
        "inputs.artifact_slug" to artifactSlug,
        "inputs.assembly_version" to version,
        "inputs.git_sha_short" to "316d68e",
        "inputs.$signingFlagName" to signingFlag.toString(),
        "steps.ver.outputs.version" to version,
        "steps.ver.outputs.version_code" to versionCode,
        "steps.ver.outputs.artifact_slug" to artifactSlug,
        "github.workspace" to "",
    )
}
