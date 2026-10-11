package com.pim.core.ci

import java.io.File

/**
 * 故障对照 / 行为验证共用的 fixture 仓库。
 *
 * 两条硬性要求：
 * 1. **C-2**：对照测试只对**同一 fixture 的两态**（修好 / 破坏）断言，不对「真实仓库当前是否违规」做前置断言；
 * 2. **对实现无耦合**：fixture 由测试自己写出的最小工作流构成（不粘贴真实文件文本），
 *    因此实现阶段改真实 YAML 之后这些判据自查用例仍然成立，不需要「顺手改测试」再 review。
 *
 * 需要「真实文件」的地方只有一处，且是不随实现变化的权威产物：
 * 契约 `contract/openapi.json` 与其 `README.md` 的 sha256 校验。
 */
object CiFixtures {

    /** `$` 的别名，便于在 Kotlin 字符串里写 shell 变量。 */
    const val D = "$"

    private val DEFAULT_MODULE_DIRS = listOf(
        "src/client-android/app/src/test",
        "src/client-android/core/src/test",
        "src/client-shell-android/app/src/test",
    )

    fun syntheticRepo(
        label: String,
        ci: String,
        buildAndroid: String? = null,
        buildShellAndroid: String? = null,
        agents: String? = null,
        extraFiles: Map<String, String> = emptyMap(),
        moduleDirs: List<String> = DEFAULT_MODULE_DIRS,
    ): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "pim-ci-synth-$label-${System.nanoTime()}")
        dir.deleteRecursively()
        fun write(relative: String, content: String) {
            val file = File(dir, relative)
            file.parentFile?.mkdirs()
            file.writeText(content.trimIndent() + "\n")
        }
        write(".github/workflows/ci.yml", ci)
        buildAndroid?.let { write(".github/workflows/build-android.yml", it) }
        buildShellAndroid?.let { write(".github/workflows/build-shell-android.yml", it) }
        agents?.let { write("AGENTS.md", it) }
        extraFiles.forEach { (relative, content) -> write(relative, content) }
        moduleDirs.forEach { File(dir, it).mkdirs() }
        return dir
    }

    /** 文本替换；找不到片段直接失败（防止 patch 静默失效）。 */
    fun patch(dir: File, relative: String, from: String, to: String, replaceAll: Boolean = false) {
        val file = File(dir, relative)
        check(file.isFile) { "fixture 里没有文件：$relative" }
        val text = file.readText()
        check(text.contains(from)) { "fixture 里找不到待替换片段：$relative / ${from.take(80)}…" }
        file.writeText(if (replaceAll) text.replace(from, to) else text.replaceFirst(from, to))
    }

    /** 行级改写：把所有满足 [selector] 的行替换为 [replacement]（空串 = 删除该行）。 */
    fun rewriteLines(dir: File, relative: String, selector: (String) -> Boolean, replacement: String) {
        val file = File(dir, relative)
        check(file.isFile) { "fixture 里没有文件：$relative" }
        val lines = file.readLines()
        check(lines.any(selector)) { "fixture 里没有匹配行：$relative" }
        file.writeText(lines.joinToString("\n") { if (selector(it)) replacement else it } + "\n")
    }

    /** 最小但结构完整的 workflow 模板；两态由参数切换。全部用显式行拼接，避免缩进工具带来的歧义。 */
    object Templates {

        fun gradlewTasks(includeCore: Boolean): String =
            if (includeCore) ":app:testDebugUnitTest :core:testDebugUnitTest" else ":app:testDebugUnitTest"

        /**
         * 最小 CI：resolve-version / 两个可复用构建调用 / gate（必需 job 全成功的聚合）/ release。
         */
        fun ci(
            releaseCondition: String,
            releaseNeeds: String = "resolve-version, build-android, build-shell-android",
            passSigningFlag: Boolean = false,
            extraJobs: String = "",
            gateNeeds: String = "resolve-version, build-android, build-shell-android",
            extraGateSteps: String = "",
            /** 额外一个必须被 gate 逐 need 消费的 job id（用于 PR 元数据门禁的 fixture）。 */
            extraConsumedNeed: String? = null,
            /**
             * `release` 作业里产出 `version.json` 的步骤（A2：真正发版必须复用与 PR 侧**同一个**入口脚本，
             * 内联第二份生成逻辑不算）。默认的最小 `echo` 步骤在 A2 判据下会被报「未复用入口脚本」，
             * 因此只有需要 A2 判据为绿的 fixture 才需要传这个参数。
             */
            releaseVersionJsonStep: String = "      - run: echo release",
        ): String = buildString {
            appendLine("name: CI")
            appendLine("on:")
            appendLine("  push:")
            appendLine("    branches: [master]")
            appendLine("  pull_request:")
            appendLine("    branches: [master]")
            // B2（review 已批准）：PR body 被改坏后旧 SHA 上的 success 不能继续算最新 check，
            // 因此 `pull_request` 必须显式包含 `edited`（默认只有 opened/synchronize/reopened）。
            appendLine("    types: [opened, synchronize, reopened, edited]")
            appendLine("  workflow_dispatch:")
            appendLine("    inputs:")
            appendLine("      force_all: { required: false, default: true, type: boolean }")
            appendLine("permissions:")
            appendLine("  contents: read")
            appendLine("jobs:")
            appendLine("  resolve-version:")
            appendLine("    runs-on: ubuntu-latest")
            appendLine("    outputs:")
            appendLine("      version: ${D}{{ steps.ver.outputs.version }}")
            appendLine("      version_code: ${D}{{ steps.ver.outputs.version_code }}")
            appendLine("      artifact_slug: ${D}{{ steps.ver.outputs.artifact_slug }}")
            appendLine("      release_tag: ${D}{{ steps.ver.outputs.release_tag }}")
            appendLine("      is_release: ${D}{{ steps.ver.outputs.is_release }}")
            appendLine("    steps:")
            appendLine("      - run: echo resolve")
            for ((jobId, workflow) in listOf(
                "build-android" to "build-android.yml",
                "build-shell-android" to "build-shell-android.yml",
            )) {
                appendLine("  $jobId:")
                appendLine("    needs: [resolve-version]")
                appendLine("    uses: ./.github/workflows/$workflow")
                appendLine("    with:")
                appendLine("      version: ${D}{{ needs.resolve-version.outputs.version }}")
                if (passSigningFlag) {
                    appendLine("      require_signing: ${D}{{ needs.resolve-version.outputs.is_release == 'true' }}")
                }
                appendLine("    secrets: inherit")
            }
            if (extraJobs.isNotBlank()) {
                extraJobs.trimIndent().lines().forEach { appendLine("  $it") }
            }
            appendLine("  gate:")
            appendLine("    name: CI Gate")
            appendLine("    needs: [$gateNeeds]")
            appendLine("    if: always()")
            appendLine("    runs-on: ubuntu-latest")
            appendLine("    steps:")
            appendLine("      - name: Evaluate required jobs")
            appendLine("        env:")
            appendLine("          R_RESOLVE: ${D}{{ needs.resolve-version.result }}")
            appendLine("          R_APK: ${D}{{ needs.build-android.result }}")
            appendLine("          R_SHELL: ${D}{{ needs.build-shell-android.result }}")
            if (extraConsumedNeed != null) {
                appendLine("          R_EXTRA: ${D}{{ needs.$extraConsumedNeed.result }}")
            }
            appendLine("        run: |")
            appendLine("          set -uo pipefail")
            appendLine("          fail=0")
            appendLine("          check () { if [ \"${D}2\" != \"success\" ]; then echo \"bad ${D}1\"; fail=1; fi; }")
            appendLine("          check \"resolve-version\" \"${D}R_RESOLVE\"")
            appendLine("          check \"build-android\" \"${D}R_APK\"")
            appendLine("          check \"build-shell-android\" \"${D}R_SHELL\"")
            if (extraConsumedNeed != null) {
                appendLine("          check \"$extraConsumedNeed\" \"${D}R_EXTRA\"")
            }
            appendLine("          if [ \"${D}fail\" -ne 0 ]; then exit 1; fi")
            if (extraGateSteps.isNotBlank()) {
                extraGateSteps.lines().forEach { appendLine(it) }
            }
            appendLine("  release:")
            appendLine("    if: $releaseCondition")
            appendLine("    needs: [$releaseNeeds]")
            appendLine("    runs-on: ubuntu-latest")
            appendLine("    permissions:")
            appendLine("      contents: write")
            appendLine("    steps:")
            appendLine(releaseVersionJsonStep)
        }

        /**
         * 最小可复用构建 workflow（app / shell 共用，靠参数区分）。
         */
        fun buildWorkflow(
            projectRoot: String,
            secretBase: String,
            gradleTasks: String,
            flagInput: String? = null,
            flagEnv: Boolean = false,
            failClosed: Boolean = false,
            shellBash: Boolean = false,
            inScriptPipefail: Boolean = false,
            verifyGate: Boolean = false,
            stageApkReadsFlag: Boolean = flagEnv,
            // 上传产物的 artifact 名前缀；必须与入口脚本的 `dl/<prefix>*/*.apk` glob 对应。
            artifactName: String = if (projectRoot.contains("shell")) "pim-shell-android" else "pim-android",
        ): String = buildString {
            val flagEnvLine = if (flagEnv && flagInput != null) {
                "          PIM_IS_RELEASE: ${D}{{ inputs.$flagInput }}"
            } else {
                null
            }
            // M-F：标志挂进 env 之外，脚本必须**真的读取**它，否则「引用 ≠ 使用」会假绿。
            val flagRead = if (flagEnvLine != null) "          echo \"require_signing=${D}{PIM_IS_RELEASE:-false}\"" else null
            appendLine("name: Build Android APK")
            appendLine("on:")
            appendLine("  workflow_call:")
            appendLine("    inputs:")
            appendLine("      version: { required: true, type: string }")
            if (flagInput != null) appendLine("      $flagInput: { required: true, type: boolean }")
            appendLine("  workflow_dispatch:")
            appendLine("    inputs:")
            appendLine("      version: { required: false, default: '' }")
            if (flagInput != null) appendLine("      $flagInput: { required: false, default: false, type: boolean }")
            appendLine("jobs:")
            appendLine("  build:")
            appendLine("    runs-on: ubuntu-latest")
            appendLine("    defaults:")
            appendLine("      run:")
            appendLine("        working-directory: $projectRoot")
            appendLine("    steps:")
            appendLine("      - uses: actions/checkout@v4")
            appendLine("      - name: Decode signing key")
            appendLine("        env:")
            appendLine("          ${secretBase}_KEYSTORE_BASE64: ${D}{{ secrets.${secretBase}_KEYSTORE_BASE64 }}")
            flagEnvLine?.let { appendLine(it) }
            appendLine("        run: |")
            flagRead?.let { appendLine(it) }
            appendLine("          if [ -z \"${D}{${secretBase}_KEYSTORE_BASE64:-}\" ]; then")
            appendLine("            echo \"::warning::${secretBase}_KEYSTORE_BASE64 is not set\"")
            if (failClosed) {
                appendLine("            if [ \"${D}{PIM_IS_RELEASE:-false}\" = \"true\" ]; then")
                appendLine("              echo \"::error::正式发布必须配置 ${secretBase}_KEYSTORE_BASE64\"")
                appendLine("              exit 1")
                appendLine("            fi")
            }
            appendLine("            exit 0")
            appendLine("          fi")
            appendLine("          mkdir -p \"${D}RUNNER_TEMP/signing\"")
            appendLine("      - name: Unit tests & build release (parallel)")
            appendLine("        env:")
            appendLine("          ANDROID_KEYSTORE_PASSWORD: ${D}{{ secrets.${secretBase}_KEYSTORE_PASSWORD }}")
            flagEnvLine?.let { appendLine(it) }
            appendLine("        run: |")
            flagRead?.let { appendLine(it) }
            appendLine("          if [ -n \"${D}{ANDROID_KEYSTORE_FILE:-}\" ]; then")
            appendLine("            for name in ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do")
            appendLine("              if [ -z \"${D}{!name:-}\" ]; then echo \"::error::${D}name is not set.\"; exit 1; fi")
            appendLine("            done")
            appendLine("          fi")
            if (failClosed) {
                appendLine("          if [ \"${D}{PIM_IS_RELEASE:-false}\" = \"true\" ] && [ -z \"${D}{ANDROID_KEYSTORE_FILE:-}\" ]; then")
                appendLine("            echo \"::error::正式发布必须配置签名密钥\"")
                appendLine("            exit 1")
                appendLine("          fi")
            }
            appendLine("          ./gradlew $gradleTasks --no-daemon")
            appendLine("      - name: Stage APK")
            flagEnvLine?.let { appendLine("        env:"); appendLine(it) }
            appendLine("        run: |")
            if (flagEnvLine != null && stageApkReadsFlag) flagRead?.let { appendLine(it) }
            appendLine("          mkdir -p build/artifacts")
            appendLine("          SRC=\"app/build/outputs/apk/release/app-release-unsigned.apk\"")
            appendLine("          cp \"${D}SRC\" build/artifacts/pim.apk || true")
            appendLine("      - name: Log signing cert fingerprint")
            appendLine("        working-directory: $projectRoot")
            if (shellBash) appendLine("        shell: bash")
            if (flagEnvLine != null) {
                appendLine("        env:")
                appendLine(flagEnvLine)
            }
            appendLine("        run: |")
            if (inScriptPipefail) appendLine("          set -o pipefail")
            flagRead?.let { appendLine(it) }
            if (verifyGate) {
                appendLine("          if [ \"${D}{PIM_IS_RELEASE:-false}\" != \"true\" ]; then")
                appendLine("            echo \"::notice::非发布构建跳过验签强制\"")
                appendLine("            exit 0")
                appendLine("          fi")
            }
            appendLine("          APK=${D}(ls build/artifacts/*.apk | head -n1)")
            appendLine("          apksigner verify --print-certs \"${D}APK\" | tee build/artifacts/signing-certs.txt")
            // 真实产物上传（A3：发布纯逻辑入口必须消费这些真实构建产物，判据按 artifact 名/路径核对）。
            appendLine("      - name: Upload APK")
            appendLine("        uses: actions/upload-artifact@v4")
            appendLine("        with:")
            appendLine("          name: $artifactName-v${D}{{ inputs.version }}")
            appendLine("          path: $projectRoot/build/artifacts/*")
        }

        fun agents(docsOnlyClause: String): String = buildString {
            appendLine("# Repository Git Hygiene")
            appendLine()
            appendLine("## Pull Request Descriptions Feed The Release Changelog")
            appendLine()
            appendLine("- $docsOnlyClause")
        }

        val SETTINGS_ANDROID = "rootProject.name = \"PimClientAndroid\"\ninclude(\":app\")\ninclude(\":core\")"

        val SETTINGS_SHELL = "rootProject.name = \"pim-shell-android\"\ninclude(\":app\")"

        /** 一个「执行 version.json 生成/校验」的合规发布纯逻辑入口（fixture 用）。 */
        fun releasePureLogicScript(): String = buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("set -euo pipefail")
            appendLine("V=\"${D}1\"; VC=\"${D}2\"; TAG=\"${D}3\"; REPO=\"${D}4\"")
            appendLine("BASE=\"https://github.com/${D}REPO/releases/download/${D}TAG\"")
            appendLine("mkdir -p out")
            appendLine("APK=\"${D}(ls dl/pim-android-*/*.apk | head -n1)\"")
            appendLine("cp \"${D}APK\" \"out/pim-android-v${D}V.apk\"")
            appendLine("SHELL_APK=\"${D}(ls dl/pim-shell-android-*/*.apk | head -n1)\"")
            appendLine("cp \"${D}SHELL_APK\" \"out/pim-shell-android-v${D}V.apk\"")
            appendLine("jq -n --arg v \"${D}V\" --arg vc \"${D}VC\" --arg base \"${D}BASE\" \\")
            appendLine("  --arg a \"pim-android-v${D}V.apk\" --arg s \"pim-shell-android-v${D}V.apk\" \\")
            appendLine("  '{version: ${D}v, versionCode: (${D}vc|tonumber), releasedAt: (now|todateiso8601),")
            appendLine("    assets: {")
            appendLine("      android:      {version: ${D}v, url: (${D}base + \"/\" + ${D}a)},")
            appendLine("      shellAndroid: {version: ${D}v, url: (${D}base + \"/\" + ${D}s)}")
            appendLine("    }}' > out/version.json")
        }

        /** 发布纯逻辑校验 job（fixture 用；脚本名故意与阶段 A 提案不同，证明判据不绑文件名）。 */
        fun releasePureLogicJob(scriptPath: String = "scripts/ci/verify-release-artifacts.sh"): String = buildString {
            appendLine("release-logic:")
            appendLine("  runs-on: ubuntu-latest")
            appendLine("  needs: [resolve-version, build-android, build-shell-android]")
            appendLine("  permissions:")
            appendLine("    contents: read")
            appendLine("  steps:")
            appendLine("    - uses: actions/checkout@v4")
            appendLine("    - name: Download build artifacts")
            appendLine("      uses: actions/download-artifact@v4")
            appendLine("      with:")
            appendLine("        path: dl")
            appendLine("    - name: Validate release naming and version.json")
            appendLine("      run: bash $scriptPath \"${D}{{ needs.resolve-version.outputs.version }}\" \"${D}{{ needs.resolve-version.outputs.version_code }}\" \"${D}{{ needs.resolve-version.outputs.release_tag }}\" \"${D}{{ github.repository }}\"")
        }

        /**
         * 一个「执行版本自测 + 契约新鲜度、且无写权限」的候选 job（fixture 用）。
         * 它是否合规取决于**是否被 required gate 真正消费**：只挂 need 不读 result 时不通过（RED-2）。
         */
        fun danglingCheckJob(jobId: String = "ci-script-checks"): String = buildString {
            appendLine("$jobId:")
            appendLine("  runs-on: ubuntu-latest")
            appendLine("  needs: [resolve-version]")
            appendLine("  permissions:")
            appendLine("    contents: read")
            appendLine("  steps:")
            appendLine("    - uses: actions/checkout@v4")
            appendLine("    - name: Version script self-test")
            appendLine("      run: bash scripts/ci/test-resolve-version.sh")
            appendLine("    - name: Contract freshness")
            appendLine("      run: |")
            appendLine("        bash scripts/ci/fetch-contract.sh")
            appendLine("        git diff --quiet -- contract/openapi.json")
        }

        /**
         * PR 元数据校验 job（F-A-10 fixture 用，脚本名与阶段 A 提案不同，证明判据不绑文件名）。
         *
         * 设计要点（fixture 就是设计接口的参考实现）：
         * - 无 `contents: write`；不在 job 级加 `if:`（push 上 job 被跳过会让 required gate 看到 skipped）；
         * - 三个约定输入变量从当前事件负载接线到 step env；
         * - 逻辑放在 `scripts/` 下的仓库脚本里（判据会读它并执行）。
         */
        fun prMetadataJob(
            scriptPath: String = "scripts/ci/verify-pr-metadata.sh",
            jobId: String = "pr-metadata",
            needs: String = "resolve-version",
        ): String = buildString {
            appendLine("$jobId:")
            appendLine("  runs-on: ubuntu-latest")
            appendLine("  needs: [$needs]")
            appendLine("  permissions:")
            appendLine("    contents: read")
            appendLine("  steps:")
            appendLine("    - uses: actions/checkout@v4")
            appendLine("    - name: Verify PR description sections and branch naming")
            appendLine("      env:")
            appendLine("        ${PrMetadataChecks.ENV_EVENT_NAME}: ${D}{{ github.event_name }}")
            appendLine("        ${PrMetadataChecks.ENV_PR_BODY}: ${D}{{ github.event.pull_request.body }}")
            appendLine("        ${PrMetadataChecks.ENV_HEAD_REF}: ${D}{{ github.event.pull_request.head.ref }}")
            appendLine("      run: bash $scriptPath")
        }

        /** PR 元数据校验的参考脚本（只读当前事件负载，不查历史 PR，不调用 gh）。 */
        fun prMetadataScript(): String = buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("set -euo pipefail")
            appendLine("EVENT_NAME=\"${D}{${PrMetadataChecks.ENV_EVENT_NAME}:-}\"")
            appendLine("PR_BODY=\"${D}{${PrMetadataChecks.ENV_PR_BODY}:-}\"")
            appendLine("PR_HEAD_REF=\"${D}{${PrMetadataChecks.ENV_HEAD_REF}:-}\"")
            appendLine("")
            appendLine("# 非 PR 事件没有 PR body 可校验：本步骤自行成功，不靠 job 级 if 跳过。")
            appendLine("if [ \"${D}EVENT_NAME\" != \"pull_request\" ]; then")
            appendLine("  echo \"非 pull_request 事件（${D}EVENT_NAME）：没有 PR 元数据需要校验。\"")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("")
            appendLine("fail=0")
            appendLine("missing=\"\"")
            appendLine("# 四段标题必须是**真实 heading 行**（整行等于标题，允许行尾空白）：")
            appendLine("# 只在正文里内联引用（例如「见 `## 测试 / Tests` 章节」）不算满足（review B3）。")
            appendLine("while IFS= read -r heading; do")
            appendLine("  [ -n \"${D}heading\" ] || continue")
            appendLine("  if ! printf '%s\\n' \"${D}PR_BODY\" | sed 's/[[:space:]]*${D}//' | grep -qxF -- \"${D}heading\"; then")
            appendLine("    missing=\"${D}missing${D}heading\"${D}'\\n'")
            appendLine("    fail=1")
            appendLine("  fi")
            appendLine("done <<'HEADINGS'")
            PrMetadataChecks.REQUIRED_HEADINGS.forEach { appendLine(it) }
            appendLine("HEADINGS")
            appendLine("if [ \"${D}fail\" -ne 0 ]; then")
            appendLine("  printf '::error::PR 描述缺少必需的双语章节行（AGENTS.md 四段）：\\n%s' \"${D}missing\" >&2")
            appendLine("fi")
            appendLine("")
            appendLine("# 分支命名：agent 分支 {agent}-{os}/{topic}（os ∈ win/linux）、工具自动分支 task/<n>，")
            appendLine("# 以及依赖更新工具分支 dependabot/** 与 renovate/**（AGENTS.md 的既有工具例外集合）。")
            appendLine("if ! printf '%s' \"${D}PR_HEAD_REF\" | grep -Eq '^(task/[0-9]+|[A-Za-z0-9][A-Za-z0-9._-]*-(win|linux)/[A-Za-z0-9][A-Za-z0-9._-]*|(dependabot|renovate)/[A-Za-z0-9][A-Za-z0-9._/-]*)$'; then")
            appendLine("  echo \"::error::PR head 分支 '${D}PR_HEAD_REF' 不符合 {agent}-{os}/{topic}、task/<n> 或依赖更新工具分支命名\" >&2")
            appendLine("  fail=1")
            appendLine("fi")
            appendLine("")
            appendLine("[ \"${D}fail\" -eq 0 ] || exit 1")
            appendLine("echo \"PR 元数据校验通过。\"")
        }

        /** gate 同 job 内追加的 step（已含 6 空格缩进，直接拼进 `steps:` 之下）。 */
        const val VERSION_SELFTEST_STEP =
            "      - name: Version script self-test\n" +
                "        run: bash scripts/ci/test-resolve-version.sh"

        const val CONTRACT_FRESHNESS_STEP =
            "      - name: Contract freshness\n" +
                "        run: |\n" +
                "          bash scripts/ci/fetch-contract.sh\n" +
                "          git diff --quiet -- contract/openapi.json"
    }
}
