plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}

// ---------------------------------------------------------------------------
// 编译期契约门禁：verifyContractEndpoints
//
// 为什么要有它：DTO 由契约生成后，**字段名/类型写错**已经会在编译期报错；但
// `@GET("pc/whatever")` 这种**契约里不存在的端点**在 Retrofit 眼里只是字符串，
// 不写门禁就只能等运行时打真实后端才炸 —— 正是工单要堵的那类「编造接口」。
//
// 这个任务把每个模块 `src/**/*.kt` 里的 Retrofit 注解与仓内契约快照
// （`contract/openapi.json`）逐个核对，并挂在编译任务之前，因此：
//   * 端点在契约里不存在 / 方法不对 → 编译失败；
//   * `@Path` 名字与路径模板占位符对不上 → 编译失败（Retrofit 原本只在运行时炸）；
//   * `@Query` 名字在契约里不存在 → 编译失败；
//   * `@Body` / 返回类型与契约该 operation 的 schema 对不上（包括用 `String`/`Unit` 这种非契约类型
//     糊过去）→ 编译失败；
//   * 用 `@HTTP(...)` / `@Url` / 扫描器看不懂的注解形态绕开核对 → 编译失败（不静默放过）。
//
// 口径与限制：
//   * 相对路径按 `PimServerEndpoints` 的 API 根路径 `/api/v1/` 解析，前导 `/` 视为绝对路径；
//   * 查询参数名**大小写不敏感**比对（契约里 analytics 系参数是 PascalCase，客户端发 camelCase，
//     ASP.NET Core 查询串绑定大小写不敏感，沿用既有口径，不为此改产品代码）；
//   * 扫描基于「掩码字符串/注释 + 括号配平」的文本分析，不是 Kotlin PSI；因此自带覆盖度自检：
//     出现次数与解析次数不一致、或出现 `@HTTP`/`@Url` 一律报错，宁可拦住也不假装核对过。
//
// 契约更新后需要新接口时：先让后端在 pim-api 实现并刷新快照，再在 ApiService 里加方法 ——
// 直接编一个端点会在这里编译失败。
// ---------------------------------------------------------------------------

private val CONTRACT_API_BASE_PATH = "/api/v1/"

private val CONTRACT_HTTP_METHODS = listOf("get", "post", "put", "delete", "patch", "head", "options")

private val RETROFIT_ANNOTATIONS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

private val RETROFIT_ANNOTATION_PREFIX =
    Regex("""@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\(\s*""")

private data class ContractOperation(
    val method: String,
    val path: String,
    val queryParameters: Set<String>,
    val hasRequestBody: Boolean,
    val requestBodyRequired: Boolean,
    /** 该 operation 请求体的根 schema 名（没有请求体时为 null）。 */
    val requestSchema: String?,
    /** 该 operation 2xx 的 JSON payload schema 名（非 JSON 响应为空集）。 */
    val responseSchemas: Set<String>
)

private data class ContractOperations(
    val byKey: Map<String, ContractOperation>,
    /** 契约 components.schemas 的全部名字：用来识别签名里引用了哪个契约类型。 */
    val schemaNames: Set<String>
)

private data class RetrofitCall(
    val method: String,
    val rawPath: String,
    val functionName: String,
    val pathVariables: Set<String>,
    val queryParameters: List<String>,
    /** `@Body` 参数的类型名（没有 `@Body` 时为 null）。 */
    val bodyTypeName: String?,
    /** 参数列表 + 返回类型里出现的契约类型名。 */
    val referencedSchemaNames: Set<String>,
    /** 返回类型里出现的契约类型名。 */
    val responseSchemaNames: Set<String>,
    /** `@Body` 类型里出现的契约类型名。 */
    val requestSchemaNames: Set<String>,
    val line: Int
) {
    val hasBody: Boolean get() = bodyTypeName != null
}

@Suppress("UNCHECKED_CAST")
private fun schemaRefName(schema: Any?): String? {
    val map = schema as? Map<*, *> ?: return null
    val ref = map["\$ref"] as? String
    if (ref != null) return ref.substringAfterLast('/')
    // 数组/信封的 data 是内联 schema 的情况：取第一个 $ref（本仓用到的 operation 都是命名 schema）。
    return when (map["type"]) {
        "array" -> schemaRefName(map["items"])
        else -> null
    }
}

private fun jsonSchemaName(response: Any?): String? {
    val responseMap = response as? Map<*, *> ?: return null
    val content = responseMap["content"] as? Map<*, *> ?: return null
    val media = content["application/json"] as? Map<*, *> ?: return null
    return schemaRefName(media["schema"])
}

@Suppress("UNCHECKED_CAST")
private fun loadContractOperations(contractFile: File): ContractOperations {
    if (!contractFile.isFile) {
        throw GradleException("契约快照不存在：$contractFile（verifyContractEndpoints 需要它）")
    }
    val root = groovy.json.JsonSlurper().parse(contractFile) as? Map<String, Any?>
        ?: throw GradleException("契约快照不是 JSON 对象：$contractFile")
    val paths = root["paths"] as? Map<String, Any?>
        ?: throw GradleException("契约快照缺少 paths：$contractFile")
    val schemas = ((root["components"] as? Map<*, *>)?.get("schemas") as? Map<*, *>) ?: emptyMap<Any?, Any?>()

    val operations = linkedMapOf<String, ContractOperation>()
    for ((path, itemValue) in paths) {
        val item = itemValue as? Map<*, *> ?: continue
        for (method in CONTRACT_HTTP_METHODS) {
            val operation = item[method] as? Map<*, *> ?: continue
            val queryParameters = linkedSetOf<String>()
            val parameters = operation["parameters"] as? List<*>
            parameters?.forEach { parameterValue ->
                val parameter = parameterValue as? Map<*, *> ?: return@forEach
                if (parameter["in"] == "query") {
                    (parameter["name"] as? String)?.let { queryParameters += it }
                }
            }
            val requestBody = operation["requestBody"] as? Map<*, *>
            val requestSchema = ((requestBody?.get("content") as? Map<*, *>)
                ?.get("application/json") as? Map<*, *>)
                ?.let { schemaRefName(it["schema"]) }
            val responseSchemas = linkedSetOf<String>()
            (operation["responses"] as? Map<*, *>)?.forEach { (status, response) ->
                if (status.toString().startsWith("2")) {
                    jsonSchemaName(response)?.let { responseSchemas += it }
                }
            }
            operations["${method.uppercase()} $path"] = ContractOperation(
                method = method.uppercase(),
                path = path,
                queryParameters = queryParameters,
                hasRequestBody = requestBody != null,
                requestBodyRequired = requestBody?.get("required") == true,
                requestSchema = requestSchema,
                responseSchemas = responseSchemas
            )
        }
    }
    if (operations.isEmpty()) {
        throw GradleException("契约快照里没有任何 operation：$contractFile")
    }
    return ContractOperations(operations, schemas.keys.filterIsInstance<String>().toSet())
}

/**
 * 把注释与字符串字面量**等长**替换成空格（保留换行），用于结构扫描。
 *
 * 为什么要这样：测试/文档里会出现 `"@GET(\"mobile/summary\")"` 这种**字符串里的注解文本**，
 * 直接扫原文会把它们当成真注解。掩码后偏移量与原文一一对应，注解参数再从原文取，
 * 既不会误报，也不会漏读真实参数值。
 */
private fun maskCommentsAndStrings(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    fun blank(ch: Char) { out.append(if (ch == '\n') '\n' else ' ') }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                while (i < text.length && text[i] != '\n') { blank(text[i]); i++ }
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                blank(text[i]); blank(text[i + 1]); i += 2
                while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) {
                    blank(text[i]); i++
                }
                if (i < text.length) { blank(text[i]); blank(text[i + 1]); i += 2 }
            }
            c == '"' && text.startsWith("\"\"\"", i) -> {
                repeat(3) { blank(' '); i++ }
                while (i < text.length && !text.startsWith("\"\"\"", i)) { blank(text[i]); i++ }
                if (i < text.length) { repeat(3) { blank(' '); i++ } }
            }
            c == '"' -> {
                blank(' '); i++
                while (i < text.length && text[i] != '"' && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < text.length) { blank(' '); blank(' '); i += 2 } else { blank(text[i]); i++ }
                }
                if (i < text.length && text[i] == '"') { blank(' '); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

/**
 * 抓出文件里所有 Retrofit HTTP 注解，并连带读出它所属函数的参数列表与返回类型。
 *
 * `masked` 与 `original` 等长：结构（注解/函数/括号）从 `masked` 读，注解的参数值从 `original` 读。
 */
private fun findRetrofitCalls(original: String, masked: String, schemaNames: Set<String>): List<RetrofitCall> {
    val calls = mutableListOf<RetrofitCall>()
    val annotation = Regex("""@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\(\s*([^)]*)\)""")
    for (match in annotation.findAll(masked)) {
        val afterAnnotation = match.range.last + 1
        val funMatch = Regex("""\bfun\s+(\w+)\s*\(""").find(masked, afterAnnotation) ?: continue
        // 注解后面紧跟的必须是同一个函数声明：中间再出现注解说明这是另一个方法。
        val between = masked.substring(afterAnnotation, funMatch.range.first)
        if (between.contains(Regex("""@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\("""))) continue

        val openParen = masked.indexOf('(', funMatch.range.first)
        var depth = 0
        var closeParen = openParen
        while (closeParen < masked.length) {
            when (masked[closeParen]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            closeParen++
        }
        val annotationText = original.substring(match.range.first, minOf(match.range.last + 1, original.length))
        val rawPath = Regex(""""([^"]*)"""").find(annotationText)?.groupValues?.get(1) ?: continue
        val parameters = original.substring(openParen + 1, minOf(closeParen, original.length))
        val maskedParameters = masked.substring(openParen + 1, minOf(closeParen, masked.length))
        // 返回类型：参数列表右括号之后的第一个 ':' 到行尾（接口方法没有方法体，声明到行尾结束）。
        val returnType = masked.substring(minOf(closeParen + 1, masked.length))
            .let { rest -> rest.indexOf(':').takeIf { it >= 0 }?.let { rest.substring(it + 1).lineSequence().first() } }
            .orEmpty()
        val signature = maskedParameters + " " + returnType
        val bodyTypeText = Regex("""@Body\s+\w+\s*:\s*([^,)=]+)""")
            .find(maskedParameters)?.groupValues?.get(1)?.trim()
        calls += RetrofitCall(
            method = match.groupValues[1],
            rawPath = rawPath,
            functionName = funMatch.groupValues[1],
            pathVariables = Regex("""@Path\(\s*"([^"]*)"\s*\)""")
                .findAll(parameters).map { it.groupValues[1] }.toSet(),
            queryParameters = Regex("""@Query\(\s*"([^"]*)"\s*\)""")
                .findAll(parameters).map { it.groupValues[1] }.toList(),
            bodyTypeName = bodyTypeText,
            referencedSchemaNames = contractNamesIn(signature, schemaNames),
            responseSchemaNames = contractNamesIn(returnType, schemaNames),
            requestSchemaNames = contractNamesIn(bodyTypeText.orEmpty(), schemaNames),
            line = masked.take(match.range.first).count { it == '\n' } + 1
        )
    }
    return calls
}

private fun contractNamesIn(text: String, schemaNames: Set<String>): Set<String> =
    schemaNames.filter { name ->
        Regex("""(?<![\w.])""" + Regex.escape(name) + """(?![\w])""").containsMatchIn(text)
    }.toSet()

private fun resolvedPath(rawPath: String): String =
    if (rawPath.startsWith("/")) rawPath else CONTRACT_API_BASE_PATH + rawPath

/** 单个文件的覆盖度自检：看不懂的 Retrofit 形态一律报错，不允许静默跳过。 */
private fun verifyKotlinFileCoverage(
    relativeFile: String,
    masked: String,
    calls: List<RetrofitCall>,
    problems: MutableList<String>
) {
    if (Regex("""@HTTP\s*\(\s*""").containsMatchIn(masked)) {
        problems += "$relativeFile 使用了 @HTTP(...)：本门禁无法把自由形态的 method/path 与契约核对。" +
            "请改用 @GET/@POST/@PUT/@DELETE/@PATCH（要新形态请先扩展 verifyContractEndpoints）。"
    }
    if (Regex("""@Url\b""").containsMatchIn(masked)) {
        problems += "$relativeFile 使用了 @Url：动态 URL 无法在编译期核对契约，等于绕开接口门禁。"
    }
    for (name in RETROFIT_ANNOTATIONS) {
        val total = Regex("""@$name\s*\(\s*""").findAll(masked).count()
        val parsed = calls.count { it.method == name }
        if (total != parsed) {
            problems += "$relativeFile 有 $total 处 @$name，但只解析出 $parsed 处（写法超出本门禁的解析范围，" +
                "不能假装核对过）。请改成 @$name(\"path\") 形态，或扩展 verifyContractEndpoints。"
        }
    }
}

private fun verifyRetrofitCalls(
    relativeFile: String,
    call: RetrofitCall,
    operations: Map<String, ContractOperation>,
    problems: MutableList<String>
) {
    val location = "$relativeFile:${call.line} (${call.functionName})"
    val path = resolvedPath(call.rawPath)
    val operation = operations["${call.method} $path"]
    if (operation == null) {
        val samePath = operations.keys.filter { it.endsWith(" $path") }
        val hint = if (samePath.isEmpty()) {
            "契约里没有这个端点。需要新接口时不要自己编 —— 让后端在 pim-api 实现并刷新契约快照。"
        } else {
            "契约里该路径只有：${samePath.joinToString(", ")}。"
        }
        problems += "$location 调用了契约中不存在的端点：${call.method} $path。$hint"
        return
    }

    val placeholders = Regex("""\{([^}]+)}""").findAll(operation.path).map { it.groupValues[1] }.toSet()
    if (placeholders != call.pathVariables) {
        problems += "$location 的 @Path 与契约路径模板对不上：模板占位符 ${placeholders.sorted()}，代码里 ${call.pathVariables.sorted()}。"
    }

    val contractQuery = operation.queryParameters.map { it.lowercase() }.toSet()
    val unknownQuery = call.queryParameters.filterNot { it.lowercase() in contractQuery }
    if (unknownQuery.isNotEmpty()) {
        problems += "$location 使用了契约未声明的查询参数：${unknownQuery.sorted()}。" +
            "契约里该 operation 的查询参数：${operation.queryParameters.sorted()}。"
    }

    if (!operation.hasRequestBody && call.hasBody) {
        problems += "$location 契约里该 operation 没有请求体，但方法声明了 @Body（${call.bodyTypeName}）。"
    }

    // 请求体/响应类型必须是该 operation 契约里声明的那个 schema，而且**必须正好是它**：
    // 编译期类型系统只能保证「类型存在」，保证不了「这个类型就是该端点的」；
    // 只检查「有没有用到别的契约类型」还不够 —— 用 String/Unit 这种非契约类型同样会漏过去。
    val allowed = buildSet {
        operation.requestSchema?.let { add(it) }
        addAll(operation.responseSchemas)
    }
    val wrongTypes = call.referencedSchemaNames - allowed
    if (wrongTypes.isNotEmpty()) {
        val expected = if (allowed.isEmpty()) {
            "该 operation 契约里没有 JSON schema（请求/响应都是非 JSON）"
        } else {
            "契约里该 operation 的类型：${allowed.sorted()}"
        }
        problems += "$location 使用了不属于该 operation 的契约类型：${wrongTypes.sorted()}。$expected。"
    }

    val expectedRequest = setOfNotNull(operation.requestSchema)
    if (call.requestSchemaNames != expectedRequest) {
        val expected = if (expectedRequest.isEmpty()) {
            "该 operation 的请求体没有 JSON schema"
        } else {
            "契约要求 @Body 是 ${expectedRequest.sorted()}"
        }
        problems += "$location 的 @Body 类型与契约对不上：声明的是 ${call.bodyTypeName ?: "<无 @Body>"}" +
            "（契约类型 ${call.requestSchemaNames.sorted()}）。$expected。"
    }

    if (call.responseSchemaNames != operation.responseSchemas) {
        val expected = if (operation.responseSchemas.isEmpty()) {
            "该 operation 的 2xx 不是 JSON（例如 text/calendar），返回类型不应引用任何契约 JSON schema"
        } else {
            "契约 2xx payload 是 ${operation.responseSchemas.sorted()}"
        }
        problems += "$location 的返回类型与契约对不上：引用了契约类型 ${call.responseSchemaNames.sorted()}。$expected。"
    }
}

private fun verifyContractEndpoints(contractFile: File, sourcesRoot: File): List<String> {
    val contract = loadContractOperations(contractFile)
    val problems = mutableListOf<String>()
    if (!sourcesRoot.isDirectory) return emptyList()
    sourcesRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .forEach { file ->
            val text = file.readText()
            // 预筛选：允许注解名与 '(' 之间有空白（`@POST ("x")` 是合法 Kotlin）。
            if (!RETROFIT_ANNOTATION_PREFIX.containsMatchIn(text)) {
                return@forEach
            }
            val relative = file.relativeTo(sourcesRoot).path
            val masked = maskCommentsAndStrings(text)
            val calls = findRetrofitCalls(text, masked, contract.schemaNames)
            verifyKotlinFileCoverage(relative, masked, calls, problems)
            calls.forEach { call ->
                verifyRetrofitCalls(relative, call, contract.byKey, problems)
            }
        }
    return problems
}

/**
 * 门禁自检：用内存 fixture 证明扫描器**真的会红**，而不是每次编译都空转。
 *
 * 为什么把自检放进任务里（而不是另一边写个测试）：门禁逻辑是构建脚本的一部分，
 * 没有可用的测试宿主；放在任务里每次编译都重跑一遍，代价是毫秒级，换来的是
 * 「扫描器被改到不再报错」这种退化会在第一次编译就暴露。
 */
private fun verifyContractEndpointGuardSelfTest(contract: ContractOperations) {
    val samplePath = contract.byKey.keys.firstOrNull { it.startsWith("GET ") }
        ?: throw GradleException("契约门禁自检失败：契约里没有任何 GET operation")
    val sampleOperation = contract.byKey.getValue(samplePath)
    val samplePost = contract.byKey.entries.firstOrNull { entry ->
        entry.key.startsWith("POST ") && entry.value.requestSchema != null && entry.value.responseSchemas.isNotEmpty()
    } ?: throw GradleException("契约门禁自检失败：契约里没有带 JSON 请求体与 JSON 响应的 POST operation")
    val samplePostOperation = samplePost.value

    fun problemsFor(source: String): List<String> {
        val masked = maskCommentsAndStrings(source)
        val calls = findRetrofitCalls(source, masked, contract.schemaNames)
        val problems = mutableListOf<String>()
        verifyKotlinFileCoverage("fixture.kt", masked, calls, problems)
        calls.forEach { verifyRetrofitCalls("fixture.kt", it, contract.byKey, problems) }
        return problems
    }

    val positive = problemsFor(
        """
        interface Sample {
            @GET("${sampleOperation.path.removePrefix(CONTRACT_API_BASE_PATH)}")
            suspend fun sample(): ${sampleOperation.responseSchemas.firstOrNull() ?: "Unit"}
        }
        """.trimIndent()
    )
    check(positive.isEmpty()) { "契约门禁自检失败：合法调用被误报：$positive" }

    val fabricatedEndpoint = problemsFor(
        """
        interface Sample {
            @GET("pc/whatever")
            suspend fun fabricated(): Unit
        }
        """.trimIndent()
    )
    check(fabricatedEndpoint.any { it.contains("不存在的端点") }) {
        "契约门禁自检失败：编造的端点没有被拦下（扫描器退化）"
    }

    val fabricatedQuery = problemsFor(
        """
        interface Sample {
            @GET("${sampleOperation.path.removePrefix(CONTRACT_API_BASE_PATH)}")
            suspend fun sample(@Query("pcFlag") pcFlag: String? = null): Unit
        }
        """.trimIndent()
    )
    check(fabricatedQuery.any { it.contains("未声明的查询参数") }) {
        "契约门禁自检失败：编造的查询参数没有被拦下（扫描器退化）"
    }

    val dynamicForm = problemsFor(
        """
        interface Sample {
            @HTTP(method = "GET", path = "pc/whatever", hasBody = false)
            suspend fun dynamic(): Unit
        }
        """.trimIndent()
    )
    check(dynamicForm.any { it.contains("@HTTP") }) {
        "契约门禁自检失败：@HTTP 绕过形态没有被拦下（扫描器退化）"
    }

    // 注解名与 '(' 之间可以有空白（`@POST ("x")` 是合法 Kotlin），预筛选与解析都必须照样识别。
    val spacedAnnotation = problemsFor(
        """
        interface Sample {
            @POST ("pc/whatever")
            suspend fun spaced(@Body request: ${samplePostOperation.requestSchema}): Unit
        }
        """.trimIndent()
    )
    check(spacedAnnotation.any { it.contains("不存在的端点") }) {
        "契约门禁自检失败：`@POST (\"...\")` 这种空白写法绕过了扫描器"
    }

    // 返回类型用非契约类型（String/Unit）时必须被拦下：契约 2xx payload 是具体的 schema。
    val wrongReturnType = problemsFor(
        """
        interface Sample {
            @POST("${samplePostOperation.path.removePrefix(CONTRACT_API_BASE_PATH)}")
            suspend fun wrongReturn(@Body request: ${samplePostOperation.requestSchema}): String
        }
        """.trimIndent()
    )
    check(wrongReturnType.any { it.contains("返回类型与契约对不上") }) {
        "契约门禁自检失败：把返回类型写成非契约类型（String）没有被拦下"
    }

    // 请求体类型写错成另一个契约 schema 时必须被拦下。
    val wrongBodyType = problemsFor(
        """
        interface Sample {
            @POST("${samplePostOperation.path.removePrefix(CONTRACT_API_BASE_PATH)}")
            suspend fun wrongBody(@Body request: ${sampleOperation.responseSchemas.firstOrNull() ?: "Unit"}): ${
            samplePostOperation.responseSchemas.firstOrNull() ?: "Unit"
        }
        }
        """.trimIndent()
    )
    check(wrongBodyType.any { it.contains("@Body 类型与契约对不上") }) {
        "契约门禁自检失败：把 @Body 类型换成别的契约类型没有被拦下"
    }

    val stringOnly = problemsFor(
        """
        class Sample {
            fun assertShape(text: String): Boolean = text.contains("@GET(\"pc/whatever\")")
        }
        """.trimIndent()
    )
    check(stringOnly.isEmpty()) { "契约门禁自检失败：字符串字面量里的注解文本被误报：$stringOnly" }
}

private val contractSnapshotFile = rootProject.projectDir.parentFile.parentFile.resolve("contract/openapi.json")

subprojects {
    val moduleDir = projectDir
    val guard = tasks.register("verifyContractEndpoints") {
        group = "verification"
        description = "把本模块 Kotlin 源码里的 Retrofit 端点/参数/契约类型与 contract/openapi.json 核对（编译期门禁）。"
        val contractFile = contractSnapshotFile
        val sourcesRoot = moduleDir.resolve("src")
        inputs.file(contractFile).withPropertyName("contractSnapshot")
        inputs.files(fileTree(sourcesRoot) { include("**/*.kt") }).withPropertyName("kotlinSources")
        doLast {
            // 先自检扫描器本身（毫秒级），再扫真实源码。
            verifyContractEndpointGuardSelfTest(loadContractOperations(contractFile))
            val problems = verifyContractEndpoints(contractFile, sourcesRoot)
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("契约门禁未通过：以下端点/参数/类型与 contract/openapi.json 不符（共 ${problems.size} 处）：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine()
                        appendLine("契约是唯一依据：契约里没有的端点/参数/类型不能写进客户端；需要新接口请先让后端实现并刷新契约快照。")
                    }
                )
            }
        }
    }
    // 挂在编译之前：这样「编造端点」是编译失败，而不是运行到那一步才发现。
    tasks.matching { it.name == "preBuild" || it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
        .configureEach { dependsOn(guard) }
}
