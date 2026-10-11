package com.pim.core.ci

import com.pim.core.contract.RepoLayout
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-A-01（REQ-COM-04 前半 / I-3）：发布**纯逻辑** —— 资产命名与 `version.json` 生成 ——
 * 必须在 PR 上、**没有发布权限**的前提下被执行和验证，异常场景必须失败。
 *
 * 判据形态（D-3）：不绑脚本文件名，按 `CiGateChecks.findReleasePureLogicValidator` 的语义
 * 结果定位「CI Gate 传递依赖里那个生成/校验 version.json 且无 `contents: write` 的 job」，
 * 执行它**真实的 step 脚本**（就是实现要交付的仓库入口，测试与 CI 跑同一入口）。
 *
 * 故障注入：
 * - 缺安卓/壳产物 → 入口必须非 0；
 * - `version.json` 生成本身失败（version_code 不是数字，`jq tonumber` 必然失败）→ 入口必须非 0；
 * - 沙箱 PATH 前置一个「一旦被调用就失败」的假 `gh`，证明纯逻辑不发版（因此 PR 上可安全运行）。
 */
class ReleasePureLogicTest {

    private val root = RepoLayout.root()
    private val validator: CiGateChecks.PureLogicValidator? by lazy {
        CiGateChecks.findReleasePureLogicValidator(root)
    }

    private fun requireEntry(): CiGateChecks.PureLogicValidator =
        validator ?: error(
            "RED（F-A-01 / I-3）：CI Gate 的传递依赖里找不到「生成/校验 version.json 且无 contents: write」的 " +
                "发布纯逻辑入口 job。审计验收要求：在一个 PR 上让 version.json 生成失败，CI Gate 必须变红；" +
                "当前 release 作业只在 master 上跑（PR 恒被跳过），所以发布纯逻辑在合并前没有任何执行路径。",
        )

    private fun sandbox(name: String, withAndroid: Boolean = true, withShell: Boolean = true): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "pim-release-$name-${System.nanoTime()}")
        dir.deleteRecursively()
        File(dir, "bin").mkdirs()
        // 纯逻辑不应调用 gh；一旦调用，这里会以 1 退出并暴露出来
        File(dir, "bin/gh").apply {
            writeText("#!/usr/bin/env bash\necho 'GH MUST NOT BE CALLED IN PURE RELEASE LOGIC' >&2\nexit 1\n")
            setExecutable(true)
        }
        // 真实仓库入口脚本会用相对路径引用 scripts/ 下的文件
        File(root, "scripts").copyRecursively(File(dir, "scripts"), overwrite = true)
        if (withAndroid) {
            File(dir, "dl/pim-android-v26.10.16-vc100016").mkdirs()
            File(dir, "dl/pim-android-v26.10.16-vc100016/pim-android-v26.10.16-vc100016.apk")
                .writeText("fake-android-apk")
        }
        if (withShell) {
            File(dir, "dl/pim-shell-android-v26.10.16-vc100016").mkdirs()
            File(dir, "dl/pim-shell-android-v26.10.16-vc100016/pim-shell-android-v26.10.16-vc100016.apk")
                .writeText("fake-shell-apk")
        }
        return dir
    }

    private fun run(dir: File, versionCode: String = "100016"): StepRunner.Result =
        runEntry(dir, requireEntry(), versionCode)

    private fun runEntry(
        dir: File,
        entry: CiGateChecks.PureLogicValidator,
        versionCode: String = "100016",
    ): StepRunner.Result {
        val script = ExpressionSubstitution.substitute(
            entry.script,
            ExpressionSubstitution.reusableWorkflowValues() +
                ExpressionSubstitution.resolveVersionOutputs(versionCode = versionCode),
        )
        val env = mapOf("PATH" to "${File(dir, "bin").absolutePath}:${System.getenv("PATH")}")
        return StepRunner.run(
            script = script,
            workDir = dir,
            env = env,
            bashArgs = StepRunner.bashArgsFor(entry.shell),
        )
    }

    @Test
    fun `发布纯逻辑入口可在无发布权限下执行并产出正确的资产名与版本清单`() {
        val dir = sandbox("happy")
        try {
            val result = run(dir)
            assertEquals("发布纯逻辑应在无发布权限下成功。\n${result.render()}", 0, result.exitCode)

            val androidAsset = File(dir, "out/pim-android-v26.10.16.apk")
            val shellAsset = File(dir, "out/pim-shell-android-v26.10.16.apk")
            assertTrue("缺少安卓资产：out/pim-android-v26.10.16.apk", androidAsset.isFile)
            assertTrue("缺少壳资产：out/pim-shell-android-v26.10.16.apk", shellAsset.isFile)
            assertEquals("fake-android-apk", androidAsset.readText())
            assertEquals("fake-shell-apk", shellAsset.readText())

            val versionJson = Json.parseToJsonElement(File(dir, "out/version.json").readText()).jsonObject
            assertEquals("26.10.16", versionJson.getValue("version").jsonPrimitive.content)
            assertEquals("100016", versionJson.getValue("versionCode").jsonPrimitive.content)
            val assets = versionJson.getValue("assets").jsonObject
            val androidUrl = assets.getValue("android").jsonObject.getValue("url").jsonPrimitive.content
            val shellUrl = assets.getValue("shellAndroid").jsonObject.getValue("url").jsonPrimitive.content
            assertTrue(
                "version.json 里的 URL 必须指向本版本实际产出的资产名：$androidUrl",
                androidUrl.endsWith("/pim-android-v26.10.16.apk"),
            )
            assertTrue(
                "version.json 里的 URL 必须指向本版本实际产出的资产名：$shellUrl",
                shellUrl.endsWith("/pim-shell-android-v26.10.16.apk"),
            )
            assertTrue(
                "URL 应包含 release tag 目录：$androidUrl",
                androidUrl.contains("/releases/download/android-v26.10.16/"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `缺少安卓产物时发布纯逻辑入口必须失败`() {
        val dir = sandbox("no-android", withAndroid = false)
        try {
            val result = run(dir)
            assertTrue(
                "没有任何安卓 APK 时仍以 0 结束，会发出空/错误的正式版本。\n${result.render()}",
                result.exitCode != 0,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `缺少壳产物时发布纯逻辑入口必须失败`() {
        val dir = sandbox("no-shell", withShell = false)
        try {
            val result = run(dir)
            assertTrue(
                "没有任何壳 APK 时仍以 0 结束，会发出空/错误的正式版本。\n${result.render()}",
                result.exitCode != 0,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `version_json 生成失败时发布纯逻辑入口必须失败`() {
        val dir = sandbox("bad-version-code")
        try {
            // 注入：version_code 不是数字 → jq 的 (… | tonumber) 必然失败 → version.json 生成失败。
            // RED-3(a) 裁决：只要求「非 0、不发布、不产出错误的正式资产」，**不**追加
            // 「不得残留 out/version.json」这种审计验收里没有的原子写要求（自然实现
            // `jq -n … > out/version.json` 会先截断创建该文件，不该因此被判红）。
            val result = run(dir, versionCode = "not-a-number")
            assertTrue(
                "version.json 生成路径失败时入口仍以 0 结束（审计 F-A-01 的验收场景：故意让 version.json " +
                    "生成失败，Gate 必须变红）。\n${result.render()}",
                result.exitCode != 0,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `发布纯逻辑入口在 workflow 中确实被 CI Gate 消费`() {
        val entry = requireEntry()
        val ci = Workflow.ci()
        val consumed = ci.transitiveNeeds("gate") + "gate"
        assertTrue(
            "发布纯逻辑 job ${entry.jobId} 必须在 CI Gate 的传递依赖里，实际：$consumed",
            entry.jobId in consumed,
        )
        assertTrue(
            "发布纯逻辑 job ${entry.jobId} 不得带 contents: write",
            ci.job(entry.jobId)?.canWriteContents() == false,
        )
        assertTrue(
            "抽取到的入口（run 文本 + 它引用的仓库脚本）必须真的涉及 version.json：${entry.script}",
            CiGateChecks.releasePureLogicSource(root, entry.script).contains("version.json"),
        )
        // RED-2 ②：发布作业必须真正经过 required 汇总（release.needs 含 gate）或直接依赖该入口，
        // 否则「gate 变红」与「不发版」是两回事。
        val release = ci.job("release")
        assertNotNull("ci.yml 里应有 release 作业", release)
        assertTrue(
            "release 必须依赖 required 汇总 `gate` 或直接依赖发布纯逻辑入口 ${entry.jobId}，" +
                "实际 needs=${release!!.needs}（挂在 gate.needs 里不足以阻止发版）",
            release.needs.contains("gate") || release.needs.contains(entry.jobId),
        )
        assertNotNull(entry.stepName ?: entry.jobId)
    }

    // ---------- 判据自洽性：同一套执行器作用在「合规 fixture」上必须给出正确结论 ----------

    /**
     * 证明上面的 RED 不是「执行器/判据坏掉」：在 fixture 里按同一接口写一个合规入口
     * （语义等价、脚本名不同），执行器必须能接受它并正确区分正常/故障三态。
     */
    @Test
    fun `合规 fixture 的发布纯逻辑入口能被执行器接受且故障注入仍然变红`() {
        val scriptPath = "scripts/ci/verify-release-artifacts.sh"
        val fixture = CiFixtures.syntheticRepo(
            label = "pure-logic-fixed",
            ci = CiFixtures.Templates.ci(
                releaseCondition = "always() && needs.resolve-version.outputs.is_release == 'true' && " +
                    "!contains(needs.*.result, 'failure') && !contains(needs.*.result, 'cancelled') && " +
                    "!contains(needs.*.result, 'skipped')",
                extraJobs = CiFixtures.Templates.releasePureLogicJob(scriptPath),
                gateNeeds = "resolve-version, release-logic, build-android, build-shell-android",
            ),
            extraFiles = mapOf(scriptPath to CiFixtures.Templates.releasePureLogicScript()),
        )
        try {
            val entry = CiGateChecks.findReleasePureLogicValidator(fixture)
            assertNotNull("fixture 里的合规入口必须被语义识别到", entry)

            val happy = sandboxAt(fixture, entry!!, "happy")
            assertEquals("合规入口在正常输入下必须成功。\n${happy.second.render()}", 0, happy.second.exitCode)
            val versionJson = Json.parseToJsonElement(
                File(happy.first, "out/version.json").readText(),
            ).jsonObject
            assertEquals("26.10.16", versionJson.getValue("version").jsonPrimitive.content)
            assertEquals("100016", versionJson.getValue("versionCode").jsonPrimitive.content)
            happy.first.deleteRecursively()

            val missingApk = sandboxAt(fixture, entry, "no-android", withAndroid = false)
            assertTrue(
                "合规入口在缺产物时必须非 0，实际：\n${missingApk.second.render()}",
                missingApk.second.exitCode != 0,
            )
            missingApk.first.deleteRecursively()

            val badVersion = sandboxAt(fixture, entry, "bad-version", versionCode = "not-a-number")
            assertTrue(
                "合规入口在 version.json 生成失败时必须非 0，实际：\n${badVersion.second.render()}",
                badVersion.second.exitCode != 0,
            )
            badVersion.first.deleteRecursively()
        } finally {
            fixture.deleteRecursively()
        }
    }

    /** 在 fixture 根下按真实 dl/ 布局 + 假 gh 建沙箱，并执行 fixture 入口。 */
    private fun sandboxAt(
        fixture: File,
        entry: CiGateChecks.PureLogicValidator,
        label: String,
        withAndroid: Boolean = true,
        versionCode: String = "100016",
    ): Pair<File, StepRunner.Result> {
        val dir = File(fixture, "sandbox-$label").apply { deleteRecursively() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/gh").apply {
            writeText("#!/usr/bin/env bash\necho 'GH MUST NOT BE CALLED IN PURE RELEASE LOGIC' >&2\nexit 1\n")
            setExecutable(true)
        }
        File(fixture, "scripts").copyRecursively(File(dir, "scripts"))
        if (withAndroid) {
            File(dir, "dl/pim-android-v26.10.16-vc100016").mkdirs()
            File(dir, "dl/pim-android-v26.10.16-vc100016/pim-android-v26.10.16-vc100016.apk")
                .writeText("fake-android-apk")
        }
        File(dir, "dl/pim-shell-android-v26.10.16-vc100016").mkdirs()
        File(dir, "dl/pim-shell-android-v26.10.16-vc100016/pim-shell-android-v26.10.16-vc100016.apk")
            .writeText("fake-shell-apk")
        return dir to runEntry(dir, entry, versionCode)
    }

}
