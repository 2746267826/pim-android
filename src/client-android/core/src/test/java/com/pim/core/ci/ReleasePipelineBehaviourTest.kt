package com.pim.core.ci

import com.pim.core.contract.RepoLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-A-04 / REQ-AND-01 / AC-AND-01 的**行为**测试（app 与 shell 两套 APK 都覆盖，C-5）。
 *
 * 方法：把可复用 workflow 里真实的 step 脚本抽出来，按 **step 自己声明的 `shell:` 语义**
 * （C-1：缺省 = `bash -e`；`shell: bash` = `bash -eo pipefail`）在受控沙箱里执行，
 * 看真实退出码；step 有 `if:` 时先按 GitHub 语义求值（false = skipped，等价于通过）。
 * 不修改生产源码、不访问网络、不读任何真实密钥。
 *
 * 覆盖 AC-AND-01 的两半：
 * - 正式发布（签名标志 = true，即合并到 master 的发版构建）在签名密钥缺失 / 未签名时**失败关闭**；
 * - fork / 普通 PR（签名标志 = false）在没有密钥、产出未签名时仍必须能完成代码验证（不能一刀切失败）。
 *
 * 接口提案（D-1，见 `remediation/pim-android/round2/red-review.md`）：`ci.yml` 把既有的
 * `is_release` 推导成 boolean `require_signing` 传给两个可复用 workflow，签名相关 step 把它
 * 注入为环境变量（默认名 `PIM_IS_RELEASE`）。测试优先从 workflow 实际接线里**发现**该变量名，
 * 发现不到才回退到提案名 —— 这样实现换了名字仍可复用，而「workflow 完全没接线」由
 * `CiGateConformanceTest.正式发布标志必须由 is_release 推导并传给两个可复用构建工作流` 判红。
 */
class ReleasePipelineBehaviourTest {

    private val ci = Workflow.ci()
    private val app = Workflow.buildAndroid()
    private val shell = Workflow.buildShellAndroid()

    private val proposedFlagName = "PIM_IS_RELEASE"
    private val proposedInputName = "require_signing"

    /** 从 ci.yml 的 `with:` 发现传给可复用 workflow 的签名标志**输入名**；没接线时回退到提案名。 */
    private fun flagInputName(): String {
        val inputs = ci.job("build-android")?.withInputs.orEmpty().keys +
            ci.job("build-shell-android")?.withInputs.orEmpty().keys
        return inputs.firstOrNull { input ->
            input != "version" && input != "version_code" && input != "artifact_slug" &&
                input != "assembly_version" && input != "git_sha_short"
        } ?: proposedInputName
    }

    /** 从真实 workflow 接线里发现签名标志的**环境变量名**；没接线时回退到提案名。 */
    private fun flagEnvName(workflow: Workflow): String {
        val flagInput = flagInputName()
        workflow.jobs.flatMap { it.signingSteps() }.forEach { step ->
            step.env.entries.firstOrNull { it.value.contains("inputs.$flagInput") }?.let { return it.key }
        }
        return proposedFlagName
    }

    private inline fun withSandbox(name: String, block: (File) -> Unit) {
        val dir = File(System.getProperty("java.io.tmpdir"), "pim-signing-test-$name-${System.nanoTime()}")
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun jobOf(workflow: Workflow): WorkflowJob = workflow.jobs.first()

    private fun scriptOf(job: WorkflowJob, predicate: (String) -> Boolean, label: String): WorkflowStep =
        job.stepByScript(predicate) ?: error("找不到 $label 步骤（按脚本内容识别）")

    /** 按 GitHub 语义执行一个 step：先求 `if`，再按 `shell:` 执行真实脚本。 */
    private fun runStep(
        workflow: Workflow,
        step: WorkflowStep,
        sandbox: File,
        signingFlag: Boolean,
        extraEnv: Map<String, String> = emptyMap(),
        flagName: String = flagEnvName(workflow),
        inputName: String = flagInputName(),
    ): StepRunner.Result {
        val job = jobOf(workflow)
        val values = ExpressionSubstitution.reusableWorkflowValues(
            signingFlagName = flagName,
            signingFlag = signingFlag,
        )
        val condition = step.condition
        if (condition != null) {
            val context = mapOf<String, Any?>(
                "inputs.$flagName" to signingFlag,
                "inputs.require_signing" to signingFlag,
                "success()" to true,
            )
            // 兼容两种写法：`if: ${{ inputs.require_signing }}` 与裸 `inputs.require_signing`
            val expr = condition.removePrefix("\${{").removeSuffix("}}").trim()
            if (!ConditionExpression.truthy(ConditionExpression.evaluate(expr, context))) {
                return StepRunner.Result(0, "", "", step.run.orEmpty(), skipped = true)
            }
        }
        val workDir = File(sandbox, step.workingDirectory ?: job.defaultsWorkingDirectory ?: ".")
        workDir.mkdirs()
        val script = ExpressionSubstitution.substitute(step.run.orEmpty(), values)
        // M-F 加固：只有 workflow **真的**把签名标志接到这个 step（env 或 if 引用 inputs.<flag>）时才注入，
        // 否则「workflow 漏接线」会被 harness 自己塞的变量掩盖 —— 行为用例必须能抓到漏接线。
        val wiredToStep = step.env.values.any { it.contains("inputs.$inputName") } ||
            job.env.values.any { it.contains("inputs.$inputName") } ||
            step.condition?.contains("inputs.$inputName") == true ||
            job.condition?.contains("inputs.$inputName") == true
        val env = buildMap {
            put("RUNNER_TEMP", File(sandbox, "runner-temp").apply { mkdirs() }.absolutePath)
            put("GITHUB_ENV", File(sandbox, "github-env").absolutePath)
            if (wiredToStep) put(flagName, signingFlag.toString())
            putAll(extraEnv)
        }
        return StepRunner.run(step = step, script = script, workDir = workDir, env = env)
    }

    private fun fakeGradlew(projectRoot: File, exitCode: Int) {
        projectRoot.mkdirs()
        File(projectRoot, "gradlew").apply {
            writeText("#!/usr/bin/env bash\nexit $exitCode\n")
            setExecutable(true)
        }
    }

    private fun fakeApksigner(sandbox: File, exitCode: Int): File {
        val dir = File(sandbox, "android-sdk/build-tools/36.1.0").apply { mkdirs() }
        File(dir, "apksigner").apply {
            writeText(
                if (exitCode == 0) "#!/usr/bin/env bash\necho 'Verifies'\nexit 0\n"
                else "#!/usr/bin/env bash\necho 'DOES NOT VERIFY' >&2\nexit 1\n",
            )
            setExecutable(true)
        }
        return File(sandbox, "android-sdk")
    }

    private fun stageUnsignedApk(projectRoot: File) {
        val artifacts = File(projectRoot, "build/artifacts").apply { mkdirs() }
        File(artifacts, "pim-android-v26.10.16-vc100016.apk").writeText("fake-unsigned-apk")
    }

    private data class Case(
        val workflow: Workflow,
        val label: String,
        val projectRoot: String,
    )

    private val appCase = Case(app, "app", "src/client-android")
    private val shellCase = Case(shell, "shell", "src/client-shell-android")

    private fun decodeStep(case: Case) = scriptOf(
        jobOf(case.workflow),
        { it.contains("KEYSTORE_BASE64") },
        "${case.label} 解码签名密钥",
    )

    private fun buildStep(case: Case) = scriptOf(
        jobOf(case.workflow),
        { it.contains("testDebugUnitTest") },
        "${case.label} 单元测试与构建",
    )

    private fun verifyStep(case: Case) = scriptOf(
        jobOf(case.workflow),
        { it.contains("apksigner") },
        "${case.label} 验签",
    )

    // ---------- 解码签名密钥的 fail-closed（app 与 shell） ----------

    @Test
    fun `正式发布缺少签名密钥时必须失败关闭`() = decodeCase(appCase, release = true, expectZero = false)

    @Test
    fun `非发布构建缺少签名密钥时可以继续用于代码验证`() = decodeCase(appCase, release = false, expectZero = true)

    @Test
    fun `壳工作流_正式发布缺少签名密钥时必须失败关闭`() = decodeCase(shellCase, release = true, expectZero = false)

    @Test
    fun `壳工作流_非发布构建缺少签名密钥时可以继续用于代码验证`() = decodeCase(shellCase, release = false, expectZero = true)

    private fun decodeCase(case: Case, release: Boolean, expectZero: Boolean) {
        withSandbox("decode-${case.label}-$release") { dir ->
            val step = decodeStep(case)
            val result = runStep(case.workflow, step, dir, signingFlag = release)
            if (expectZero) {
                assertEquals(
                    "${case.label}：非发布（签名标志=false）不应因为缺少签名密钥而失败。\n${result.render()}",
                    0,
                    result.exitCode,
                )
            } else {
                assertTrue(
                    "${case.label}：正式发布（签名标志=true）缺密钥时仍以 0 继续，" +
                        "未签名包会被当成正式资产发布（F-A-04 fail-closed 缺失）。\n${result.render()}",
                    result.exitCode != 0,
                )
            }
        }
    }

    // ---------- 构建步骤的密钥一致性（app 与 shell） ----------

    @Test
    fun `有密钥文件但缺配套密码或别名时必须失败`() = buildCase(appCase, release = true, keyFile = "present", expectZero = false)

    @Test
    fun `正式发布无密钥文件时构建步骤必须失败关闭`() = buildCase(appCase, release = true, keyFile = "", expectZero = false)

    @Test
    fun `非发布构建无密钥文件时可以继续跑完代码验证`() = buildCase(appCase, release = false, keyFile = "", expectZero = true)

    @Test
    fun `壳工作流_正式发布无密钥文件时构建步骤必须失败关闭`() = buildCase(shellCase, release = true, keyFile = "", expectZero = false)

    @Test
    fun `壳工作流_非发布构建无密钥文件时可以继续跑完代码验证`() = buildCase(shellCase, release = false, keyFile = "", expectZero = true)

    private fun buildCase(case: Case, release: Boolean, keyFile: String, expectZero: Boolean) {
        withSandbox("build-${case.label}-$release-${keyFile.ifEmpty { "nokey" }}") { dir ->
            val projectRoot = File(dir, case.projectRoot)
            fakeGradlew(projectRoot, exitCode = 0)
            val step = buildStep(case)
            val result = runStep(
                case.workflow,
                step,
                dir,
                signingFlag = release,
                extraEnv = mapOf(
                    "ANDROID_KEYSTORE_FILE" to if (keyFile.isEmpty()) "" else "$dir/keystore.jks",
                    "ANDROID_KEYSTORE_PASSWORD" to "",
                    "ANDROID_KEY_ALIAS" to "",
                    "ANDROID_KEY_PASSWORD" to "",
                ),
            )
            if (expectZero) {
                assertEquals("${case.label}：非发布构建不应因缺少密钥而失败。\n${result.render()}", 0, result.exitCode)
            } else {
                assertTrue(
                    "${case.label}：应 fail-closed 的场景仍以 0 继续。\n${result.render()}",
                    result.exitCode != 0,
                )
            }
        }
    }

    // ---------- 验签步骤的失败传播（pipefail 判据按 step 的 shell 求值） ----------

    @Test
    fun `签名校验失败必须在步骤声明的 shell 语义下传播`() = verifyCase(appCase, release = true, apksignerExit = 1, expectZero = false)

    @Test
    fun `壳工作流_签名校验失败必须在步骤声明的 shell 语义下传播`() = verifyCase(shellCase, release = true, apksignerExit = 1, expectZero = false)

    @Test
    fun `非发布未签名时验签步骤不得使 job 失败`() = verifyCase(appCase, release = false, apksignerExit = 1, expectZero = true)

    @Test
    fun `壳工作流_非发布未签名时验签步骤不得使 job 失败`() = verifyCase(shellCase, release = false, apksignerExit = 1, expectZero = true)

    @Test
    fun `签名校验步骤在验签成功时必须通过`() = verifyCase(appCase, release = true, apksignerExit = 0, expectZero = true)

    @Test
    fun `壳工作流_签名校验步骤在验签成功时必须通过`() = verifyCase(shellCase, release = true, apksignerExit = 0, expectZero = true)

    private fun verifyCase(case: Case, release: Boolean, apksignerExit: Int, expectZero: Boolean) {
        withSandbox("verify-${case.label}-$release-$apksignerExit") { dir ->
            val projectRoot = File(dir, case.projectRoot)
            stageUnsignedApk(projectRoot)
            val androidHome = fakeApksigner(dir, apksignerExit)
            val step = verifyStep(case)
            val result = runStep(
                case.workflow,
                step,
                dir,
                signingFlag = release,
                extraEnv = mapOf("ANDROID_HOME" to androidHome.absolutePath),
            )
            if (expectZero) {
                assertEquals(
                    "${case.label}：该场景（release=$release, apksigner_exit=$apksignerExit）不应失败。\n${result.render()}",
                    0,
                    result.exitCode,
                )
            } else {
                assertTrue(
                    "${case.label}：apksigner 返回 $apksignerExit 时该步骤仍以 0 结束 —— 失败被 `| tee` 管道吞掉，" +
                        "或 step 未按声明 shell 执行（C-1/C-5）。\n${result.render()}",
                    result.exitCode != 0,
                )
            }
        }
    }

    // ---------- M-F：Stage APK 必须按签名标志真正区分正式发布 / 非发布 ----------

    private fun stageStep(case: Case) = scriptOf(
        jobOf(case.workflow),
        { it.contains("app-release-unsigned") },
        "${case.label} Stage APK",
    )

    /** 在沙箱里放好 APK 产物（signed = AGP 有签名密钥时的 app-release.apk）。 */
    private fun stageApkFixtures(projectRoot: File, signed: Boolean, unsigned: Boolean) {
        val dir = File(projectRoot, "app/build/outputs/apk/release").apply { mkdirs() }
        if (signed) File(dir, "app-release.apk").writeText("fake-signed-apk")
        if (unsigned) File(dir, "app-release-unsigned.apk").writeText("fake-unsigned-apk")
    }

    @Test
    fun `正式发布产出未签名 APK 时 Stage APK 必须失败关闭`() =
        stageCase(appCase, release = true, signed = false, unsigned = true, expectZero = false)

    @Test
    fun `正式发布已签名 APK 时 Stage APK 必须通过`() =
        stageCase(appCase, release = true, signed = true, unsigned = false, expectZero = true)

    @Test
    fun `非发布产出未签名 APK 时 Stage APK 可以继续用于代码验证`() =
        stageCase(appCase, release = false, signed = false, unsigned = true, expectZero = true)

    @Test
    fun `壳工作流_正式发布产出未签名 APK 时 Stage APK 必须失败关闭`() =
        stageCase(shellCase, release = true, signed = false, unsigned = true, expectZero = false)

    @Test
    fun `壳工作流_非发布产出未签名 APK 时 Stage APK 可以继续`() =
        stageCase(shellCase, release = false, signed = false, unsigned = true, expectZero = true)

    private fun stageCase(case: Case, release: Boolean, signed: Boolean, unsigned: Boolean, expectZero: Boolean) {
        withSandbox("stage-${case.label}-$release-$signed$unsigned") { dir ->
            stageApkFixtures(File(dir, case.projectRoot), signed = signed, unsigned = unsigned)
            val result = runStep(case.workflow, stageStep(case), dir, signingFlag = release)
            if (expectZero) {
                assertEquals(
                    "${case.label}：该场景（release=$release, signed=$signed）Stage APK 不应失败。\n${result.render()}",
                    0,
                    result.exitCode,
                )
            } else {
                assertTrue(
                    "${case.label}：正式发布（签名标志=true）下产出未签名 APK 并以 0 结束 —— " +
                        "未签名包会被改名成正式资产发布（F-A-04 / M-F）。\n${result.render()}",
                    result.exitCode != 0,
                )
            }
        }
    }

    // ---------- 抽取有效性守卫 ----------

    @Test
    fun `抽取到的脚本确实是工作流里的真实脚本`() {
        for (case in listOf(appCase, shellCase)) {
            assertTrue(
                "${case.label}：抽取的解码脚本不像签名解码逻辑：${decodeStep(case).run}",
                decodeStep(case).run.orEmpty().contains("KEYSTORE_BASE64"),
            )
            assertTrue(
                "${case.label}：抽取的构建脚本应包含单元测试调用",
                buildStep(case).run.orEmpty().contains("testDebugUnitTest"),
            )
            assertTrue(
                "${case.label}：抽取的验签脚本应调用 apksigner",
                verifyStep(case).run.orEmpty().contains("apksigner"),
            )
        }
        assertTrue(
            "build-android.yml 的默认工作目录应为 src/client-android",
            app.jobs.all { it.defaultsWorkingDirectory == "src/client-android" },
        )
        assertTrue(
            "build-shell-android.yml 的默认工作目录应为 src/client-shell-android",
            shell.jobs.all { it.defaultsWorkingDirectory == "src/client-shell-android" },
        )
    }

    @Test
    fun `工作流声明的 shell 语义被执行器正确解释`() {
        assertEquals(listOf("-e"), StepRunner.bashArgsFor(null))
        assertEquals(listOf("--noprofile", "--norc", "-eo", "pipefail"), StepRunner.bashArgsFor("bash"))
        // 真实执行：缺省 shell 无 pipefail，管道失败被吞；shell: bash 传播失败
        withSandbox("shell-semantics") { dir ->
            val script = "#!/usr/bin/env bash\nfalse | tee out.txt\n"
            val default = StepRunner.run(script, dir, bashArgs = StepRunner.bashArgsFor(null))
            assertEquals("缺省 shell（bash -e）应吞掉管道失败", 0, default.exitCode)
            val bash = StepRunner.run(script, dir, bashArgs = StepRunner.bashArgsFor("bash"))
            assertTrue("shell: bash 必须传播管道失败（pipefail）", bash.exitCode != 0)
        }
        // 脚本内 pipefail 是等价实现
        withSandbox("shell-pipefail-in-script") { dir ->
            val script = "set -o pipefail\nfalse | tee out.txt\n"
            val result = StepRunner.run(script, dir, bashArgs = StepRunner.bashArgsFor(null))
            assertTrue("脚本内 set -o pipefail 必须传播管道失败", result.exitCode != 0)
        }
    }

    @Test
    fun `验签步骤在 workflow 里要么声明 shell bash 要么由脚本自身开启 pipefail`() {
        for (case in listOf(appCase, shellCase)) {
            val step = verifyStep(case)
            val script = step.run.orEmpty()
            val propagates = step.shell?.trim().equals("bash", ignoreCase = true) ||
                script.contains("set -o pipefail")
            val declared = CiGateChecks.apksignerFailurePropagates(RepoLayout.root())
            if (!propagates) {
                assertTrue(
                    "当前 ${case.label} 验签步骤没有 pipefail 语义，判据必须报出该问题（RED 依据）",
                    declared.any { it.contains(".github/workflows/${if (case.label == "app") "build-android" else "build-shell-android"}.yml") },
                )
            }
        }
    }

    // ---------- 判据自洽性：在 fixture 上构造「按 F-A-04 修好」的形态，必须全绿 ----------

    /**
     * 证明 RED 不是「判据本身写死/执行器坏掉」：把同一套判据用到**构造性修好**的 fixture 上，
     * 四种组合都必须给出正确结论（release+缺密钥/未签名 → 非 0；非发布 → 0）。
     */
    @Test
    fun `按 F-A-04 修好的 fixture 必须通过同一套 fail-closed 与 pipefail 判据`() {
        val fixture = CiFixtures.syntheticRepo(
            label = "release-pipeline-fixed",
            ci = CiFixtures.Templates.ci(
                releaseCondition = "always() && needs.resolve-version.outputs.is_release == 'true' && " +
                    "!contains(needs.*.result, 'failure') && !contains(needs.*.result, 'cancelled') && " +
                    "!contains(needs.*.result, 'skipped')",
                passSigningFlag = true,
            ),
            buildAndroid = CiFixtures.Templates.buildWorkflow(
                projectRoot = "src/client-android",
                secretBase = "ANDROID",
                gradleTasks = CiFixtures.Templates.gradlewTasks(includeCore = true),
                flagInput = "require_signing",
                flagEnv = true,
                failClosed = true,
                shellBash = true,
                verifyGate = true,
            ),
            extraFiles = mapOf(
                "src/client-android/settings.gradle.kts" to CiFixtures.Templates.SETTINGS_ANDROID,
            ),
        )
        val flagName = "PIM_IS_RELEASE"
        try {
            val workflow = Workflow.load(File(fixture, ".github/workflows/build-android.yml"))
            val job = workflow.jobs.first()
            val decode = scriptOf(job, { it.contains("KEYSTORE_BASE64") }, "decode")
            val build = scriptOf(job, { it.contains("testDebugUnitTest") }, "build")
            val verify = scriptOf(job, { it.contains("apksigner") }, "verify")
            val projectRoot = File(fixture, "src/client-android").apply { mkdirs() }

            val decodeRelease = runStep(workflow, decode, fixture, signingFlag = true, flagName = flagName)
            assertTrue(
                "修好的 fixture：正式发布缺密钥必须非 0（判据必须承认该修法）。\n${decodeRelease.render()}",
                decodeRelease.exitCode != 0,
            )
            val decodePr = runStep(workflow, decode, fixture, signingFlag = false, flagName = flagName)
            assertEquals(
                "修好的 fixture：非发布缺密钥必须通过。\n${decodePr.render()}",
                0,
                decodePr.exitCode,
            )

            fakeGradlew(projectRoot, exitCode = 0)
            val buildRelease = runStep(
                workflow, build, fixture, signingFlag = true, flagName = flagName,
                extraEnv = mapOf("ANDROID_KEYSTORE_FILE" to ""),
            )
            assertTrue(
                "修好的 fixture：正式发布无密钥文件时构建步骤必须非 0。\n${buildRelease.render()}",
                buildRelease.exitCode != 0,
            )
            val buildPr = runStep(
                workflow, build, fixture, signingFlag = false, flagName = flagName,
                extraEnv = mapOf("ANDROID_KEYSTORE_FILE" to ""),
            )
            assertEquals(
                "修好的 fixture：非发布无密钥文件时构建步骤必须通过。\n${buildPr.render()}",
                0,
                buildPr.exitCode,
            )

            stageUnsignedApk(projectRoot)
            val androidHome = fakeApksigner(fixture, exitCode = 1)
            val verifyRelease = runStep(
                workflow, verify, fixture, signingFlag = true, flagName = flagName,
                extraEnv = mapOf("ANDROID_HOME" to androidHome.absolutePath),
            )
            assertTrue(
                "修好的 fixture：正式发布验签失败必须传播为步骤失败（shell: bash + pipefail 或等价修法）。\n${verifyRelease.render()}",
                verifyRelease.exitCode != 0,
            )
            val verifyPr = runStep(
                workflow, verify, fixture, signingFlag = false, flagName = flagName,
                extraEnv = mapOf("ANDROID_HOME" to androidHome.absolutePath),
            )
            assertEquals(
                "修好的 fixture：非发布未签名时验签步骤不得失败（fork 代码验证保留）。\n${verifyPr.render()}",
                0,
                verifyPr.exitCode,
            )
        } finally {
            fixture.deleteRecursively()
        }
    }
}
