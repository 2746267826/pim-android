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
//   * `@Body` / 返回类型与该 operation 契约声明的 schema **逐字结构**对不上
//     （`String`/`List<X>` 这类形状不符、用别的契约类型顶替都算）→ 编译失败；
//   * 用 `@HTTP(...)` / `@Url` / 扫描器看不懂的注解形态绕开核对 → 编译失败（不静默放过）。
//
// 口径与限制：
//   * 相对路径按 `PimServerEndpoints` 的 API 根路径 `/api/v1/` 解析，前导 `/` 视为绝对路径；
//   * 查询参数名**大小写不敏感**比对（契约里 analytics 系参数是 PascalCase，客户端发 camelCase，
//     ASP.NET Core 查询串绑定大小写不敏感，沿用既有口径，不为此改产品代码）；
//   * 扫描基于「掩码字符串/注释 + 括号配平」的文本分析，不是 Kotlin PSI。正因为不是编译器级，
//     它把"看不懂"一律当失败：注解数量与解析数量不一致、出现 `@HTTP`/`@Url` 都报错，
//     而不是假装核对过。任务每次运行都会先跑一遍 fixture 自检（走真实文件扫描入口），
//     保证扫描器不会退化成"永远 PASS"。
//
// 契约更新后需要新接口时：先让后端在 pim-api 实现并刷新快照，再在 ApiService 里加方法 ——
// 直接编一个端点会在这里编译失败。
// ---------------------------------------------------------------------------

private val CONTRACT_API_BASE_PATH = "/api/v1/"

private val CONTRACT_HTTP_METHODS = listOf("get", "post", "put", "delete", "patch", "head", "options")

private val RETROFIT_ANNOTATIONS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

/** 出现在源码任何位置（含注释分隔写法）都要被门禁看见的注解名字。 */
private val RETROFIT_ANNOTATION_PREFIX =
    Regex("""@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|HTTP)\s*\(?""")

/** 契约 2xx 不是 JSON 时允许的返回类型（Retrofit 原样把响应体交回来的载体）。 */
private val NON_JSON_RESPONSE_TYPES = setOf("ResponseBody", "Unit")

/** 契约请求体不是 JSON 时允许的 `@Body` 类型。 */
private val NON_JSON_REQUEST_TYPES = setOf("RequestBody")

private data class ContractOperation(
    val method: String,
    val path: String,
    val queryParameters: Set<String>,
    val hasRequestBody: Boolean,
    val requestBodyRequired: Boolean,
    /** 该 operation 请求体的根 schema 名（没有 JSON 请求体时为 null）。 */
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
    /** `@Body` 参数的类型（没有 `@Body` 时为 null）。 */
    val bodyType: String?,
    /** 返回类型的原文（已做空白归一化）。 */
    val returnType: String,
    /** 参数列表 + 返回类型里出现的契约类型名。 */
    val referencedSchemaNames: Set<String>,
    /** 参数列表的掩码文本（参数注解覆盖度自检用）。 */
    val maskedParameters: String,
    val line: Int
) {
    val hasBody: Boolean get() = bodyType != null
}

@Suppress("UNCHECKED_CAST")
private fun schemaRefName(schema: Any?): String? {
    val map = schema as? Map<*, *> ?: return null
    val ref = map["\$ref"] as? String
    if (ref != null) return ref.substringAfterLast('/')
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
 * 直接扫原文会把它们当成真注解；反过来，`@POST /*c*/ ("x")` 这种**注释分隔**的写法又必须被看见。
 * 掩码后偏移量与原文一一对应：结构从掩码读，参数值再从原文取，两边都不丢。
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

/** 从 `openParen` 出发做括号配平，返回配对的 `)` 下标（找不到时返回文本末尾）。 */
private fun matchingParen(text: String, openParen: Int): Int {
    var depth = 0
    var i = openParen
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return text.length
}

/**
 * 读出 `@Name("...")` 形式的注解值：**结构定位在掩码文本上**（因此注释/换行/多余空白都无所谓），
 * 取值再从等长的原文里切同一段 —— 掩码只吃掉了字符串内容，偏移不变。
 */
private fun annotationStringValues(masked: String, original: String, annotationName: String): List<String> {
    val values = mutableListOf<String>()
    val regex = Regex("""@$annotationName\s*\(""")
    for (match in regex.findAll(masked)) {
        val openParen = masked.indexOf('(', match.range.first)
        if (openParen < 0) continue
        val closeParen = matchingParen(masked, openParen)
        val slice = original.substring(openParen + 1, minOf(closeParen, original.length))
        Regex(""""([^"]*)"""").find(slice)?.let { values += it.groupValues[1] }
    }
    return values
}

/** 去掉空白与常见包名前缀，只留下类型骨架，便于做「逐字结构」比较。 */
private fun normalizeType(type: String): String =
    type.replace(Regex("""\s+"""), "")
        .removeSuffix("?")
        .replace("okhttp3.", "")
        .replace("retrofit2.", "")
        .replace("java.lang.", "")

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
        val closeParen = matchingParen(masked, openParen)
        val annotationText = original.substring(match.range.first, minOf(match.range.last + 1, original.length))
        val rawPath = Regex(""""([^"]*)"""").find(annotationText)?.groupValues?.get(1) ?: continue
        val parameters = original.substring(openParen + 1, minOf(closeParen, original.length))
        val maskedParameters = masked.substring(openParen + 1, minOf(closeParen, masked.length))
        val returnType = extractReturnType(masked, closeParen)
        val bodyType = Regex("""@Body\s*(?:\(\s*\))?\s+\w+\s*:\s*([^,)=]+)""")
            .find(maskedParameters)?.groupValues?.get(1)?.trim()
        calls += RetrofitCall(
            method = match.groupValues[1],
            rawPath = rawPath,
            functionName = funMatch.groupValues[1],
            pathVariables = annotationStringValues(maskedParameters, parameters, "Path").toSet(),
            queryParameters = annotationStringValues(maskedParameters, parameters, "Query"),
            bodyType = bodyType,
            returnType = normalizeType(returnType),
            referencedSchemaNames = contractNamesIn(maskedParameters + " " + returnType, schemaNames),
            maskedParameters = maskedParameters,
            line = masked.take(match.range.first).count { it == '\n' } + 1
        )
    }
    return calls
}

/**
 * 返回类型：参数列表的 `)` 之后第一个 `:` 起，到**括号/尖括号配平为 0 时的换行**为止。
 * 这样 `Response<\n  AuthResponseApiResponse\n>` 这类跨行泛型也能完整读到。
 */
private fun extractReturnType(masked: String, closeParen: Int): String {
    val rest = masked.substring(minOf(closeParen + 1, masked.length))
    val colon = rest.indexOf(':')
    if (colon < 0) return ""
    val type = StringBuilder()
    var angleDepth = 0
    var parenDepth = 0
    var i = colon + 1
    while (i < rest.length) {
        val c = rest[i]
        when (c) {
            '<' -> angleDepth++
            '>' -> angleDepth--
            '(' -> parenDepth++
            ')' -> parenDepth--
            '\n' -> if (angleDepth <= 0 && parenDepth <= 0 && type.isNotBlank()) break
        }
        type.append(c)
        i++
    }
    return type.toString().trim()
}

private fun contractNamesIn(text: String, schemaNames: Set<String>): Set<String> =
    schemaNames.filter { name ->
        Regex("""(?<![\w.])""" + Regex.escape(name) + """(?![\w])""").containsMatchIn(text)
    }.toSet()

private fun resolvedPath(rawPath: String): String =
    if (rawPath.startsWith("/")) rawPath else CONTRACT_API_BASE_PATH + rawPath

/**
 * 单个文件的覆盖度自检：看不懂的 Retrofit 形态一律报错，不允许静默跳过。
 *
 * 这里是「门禁会不会被绕过」的关键：扫描器看不懂的东西必须拦住，而不是当它不存在。
 */
private fun verifyKotlinFileCoverage(
    relativeFile: String,
    masked: String,
    calls: List<RetrofitCall>,
    problems: MutableList<String>
) {
    if (Regex("""@HTTP\s*\(""").containsMatchIn(masked)) {
        problems += "$relativeFile 使用了 @HTTP(...)：本门禁无法把自由形态的 method/path 与契约核对。" +
            "请改用 @GET/@POST/@PUT/@DELETE/@PATCH（要新形态请先扩展 verifyContractEndpoints）。"
    }
    if (Regex("""@Url\b""").containsMatchIn(masked)) {
        problems += "$relativeFile 使用了 @Url：动态 URL 无法在编译期核对契约，等于绕开接口门禁。"
    }
    for (name in RETROFIT_ANNOTATIONS) {
        val total = Regex("""@$name\s*\(""").findAll(masked).count()
        val parsed = calls.count { it.method == name }
        if (total != parsed) {
            problems += "$relativeFile 有 $total 处 @$name，但只解析出 $parsed 处（写法超出本门禁的解析范围，" +
                "不能假装核对过）。请改成 @$name(\"path\") 形态，或扩展 verifyContractEndpoints。"
        }
    }
}

/** 参数注解的取值也必须「看到多少就解析多少」：解析不出来的 `@Query`/`@Path`/`@Body` 一律报错。 */
private fun verifyParameterAnnotationCoverage(
    relativeFile: String,
    call: RetrofitCall,
    problems: MutableList<String>
) {
    val location = "$relativeFile:${call.line} (${call.functionName})"
    val maskedParameters = call.maskedParameters
    for (name in listOf("Path", "Query")) {
        val total = Regex("""@$name\s*\(""").findAll(maskedParameters).count()
        val parsed = if (name == "Path") call.pathVariables.size else call.queryParameters.size
        if (total != parsed) {
            problems += "$location 有 $total 处 @$name，但只解析出 $parsed 处（写法超出本门禁的解析范围）。"
        }
    }
    val bodyAnnotations = Regex("""@Body\b""").findAll(maskedParameters).count()
    if (bodyAnnotations > 0 && !call.hasBody) {
        problems += "$location 出现 @Body 但解析不出参数类型（写法超出本门禁的解析范围）。"
    }
    if (bodyAnnotations > 1) {
        problems += "$location 有 $bodyAnnotations 处 @Body：一个方法只能有一个请求体。"
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

    if (operation.hasRequestBody && !call.hasBody) {
        val suffix = if (operation.requestBodyRequired) "（契约标了 required）" else ""
        problems += "$location 契约里该 operation 有请求体$suffix，但方法没有 @Body。"
    }
    if (!operation.hasRequestBody && call.hasBody) {
        problems += "$location 契约里该 operation 没有请求体，但方法声明了 @Body（${call.bodyType}）。"
    }

    // 请求体/响应类型必须**逐字结构**等于该 operation 契约里声明的那个 schema。
    // 只比较「引用了哪些契约类型」不够：`List<X>`、`String`、`Unit` 都糊得过去。
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

    val bodyType = call.bodyType?.let(::normalizeType)
    if (operation.requestSchema != null) {
        if (bodyType != operation.requestSchema) {
            problems += "$location 的 @Body 类型与契约对不上：声明的是 ${call.bodyType ?: "<无 @Body>"}。" +
                "契约该 operation 的请求体是 ${operation.requestSchema}。"
        }
    } else if (call.hasBody && bodyType !in NON_JSON_REQUEST_TYPES) {
        problems += "$location 的 @Body 类型与契约对不上：声明的是 $bodyType。" +
            "该 operation 契约里没有 JSON 请求体，只允许 ${NON_JSON_REQUEST_TYPES.sorted()}。"
    }

    val expectedResponses = operation.responseSchemas
    val allowedResponses = buildSet {
        expectedResponses.forEach { add(it); add("Response<$it>") }
        if (expectedResponses.isEmpty()) addAll(NON_JSON_RESPONSE_TYPES)
        if (expectedResponses.isEmpty()) add("Response<ResponseBody>")
    }
    if (call.returnType !in allowedResponses) {
        val expected = if (expectedResponses.isEmpty()) {
            "该 operation 的 2xx 不是 JSON（例如 text/calendar），返回类型只允许 ${allowedResponses.sorted()}"
        } else {
            "契约 2xx payload 是 ${expectedResponses.sorted()}（可包一层 Response<...>）"
        }
        problems += "$location 的返回类型与契约对不上：${call.returnType.ifBlank { "<无法解析>" }}。$expected。"
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
            val original = file.readText()
            val masked = maskCommentsAndStrings(original)
            // 预筛选走**掩码后**的文本：注释分隔写法（`@POST /*c*/ ("x")`）与 `@HTTP(...)` 都必须被看见。
            if (!RETROFIT_ANNOTATION_PREFIX.containsMatchIn(masked)) {
                return@forEach
            }
            val relative = file.relativeTo(sourcesRoot).path
            val calls = findRetrofitCalls(original, masked, contract.schemaNames)
            verifyKotlinFileCoverage(relative, masked, calls, problems)
            calls.forEach { call ->
                verifyParameterAnnotationCoverage(relative, call, problems)
                verifyRetrofitCalls(relative, call, contract.byKey, problems)
            }
        }
    return problems
}

/**
 * 门禁自检：把 fixture **写进临时目录、走真实文件扫描入口**跑一遍，证明扫描器真的会红。
 *
 * 为什么必须走真实入口：只调用底层解析函数的话，「预筛选把整文件跳过」这类退化不会被发现
 * （第一轮 review 就是这么找到绕过写法的）。自检放在任务里、每次编译都跑，代价是毫秒级。
 */
private fun verifyContractEndpointGuardSelfTest(contractFile: File, contract: ContractOperations) {
    // fixture 用不带路径占位符的 operation，这样自检本身不必再构造 @Path 参数。
    val sampleGet = contract.byKey.entries.firstOrNull { entry ->
        entry.key.startsWith("GET ") && entry.value.responseSchemas.isNotEmpty() &&
            !entry.value.path.contains("{")
    } ?: throw GradleException("契约门禁自检失败：契约里没有带 JSON 响应且无路径参数的 GET operation")
    val samplePost = contract.byKey.entries.firstOrNull { entry ->
        entry.key.startsWith("POST ") && entry.value.requestSchema != null &&
            entry.value.responseSchemas.isNotEmpty() && !entry.value.path.contains("{")
    } ?: throw GradleException("契约门禁自检失败：契约里没有带 JSON 请求体/响应且无路径参数的 POST operation")

    val getPath = sampleGet.value.path.removePrefix(CONTRACT_API_BASE_PATH)
    val getResponse = sampleGet.value.responseSchemas.first()
    val postPath = samplePost.value.path.removePrefix(CONTRACT_API_BASE_PATH)
    val postRequest = samplePost.value.requestSchema!!
    val postResponse = samplePost.value.responseSchemas.first()

    val root = File.createTempFile("pim-contract-guard-selftest", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }
    var caseIndex = 0
    fun problemsFor(source: String): List<String> {
        val caseDir = File(root, "case${caseIndex++}").apply { mkdirs() }
        File(caseDir, "Fixture.kt").writeText(source)
        return verifyContractEndpoints(contractFile, caseDir)
    }

    try {
        val positive = problemsFor(
            """
            interface Sample {
                @GET("$getPath")
                suspend fun sample(): $getResponse

                @POST("$postPath")
                suspend fun samplePost(@Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(positive.isEmpty()) { "契约门禁自检失败：合法调用被误报：$positive" }

        val committedForm = problemsFor(
            """
            interface Sample {
                // 契约里真实存在的端点，用仓库线上风格的跨行签名与 Response<...> 包裹
                @POST(
                    "$postPath"
                )
                suspend fun samplePost(
                    @Body request: $postRequest
                ): Response<$postResponse>
            }
            """.trimIndent()
        )
        check(committedForm.isEmpty()) { "契约门禁自检失败：跨行/Response<> 的合法写法被误报：$committedForm" }

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

        val commentSeparated = problemsFor(
            """
            interface Sample {
                @POST /*c*/ ("pc/whatever")
                suspend fun fabricated(@Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(commentSeparated.any { it.contains("不存在的端点") }) {
            "契约门禁自检失败：注释分隔写法（@POST /*c*/ (\"...\")）绕过了扫描器"
        }

        val httpOnlyFile = problemsFor(
            """
            interface Sample {
                @HTTP(method = "GET", path = "pc/whatever", hasBody = false)
                suspend fun dynamic(): Unit
            }
            """.trimIndent()
        )
        check(httpOnlyFile.any { it.contains("@HTTP") }) {
            "契约门禁自检失败：只含 @HTTP 的文件被整文件跳过"
        }

        val fabricatedQuery = problemsFor(
            """
            interface Sample {
                @GET("$getPath")
                suspend fun sample(@Query("pcFlag") pcFlag: String? = null): $getResponse
            }
            """.trimIndent()
        )
        check(fabricatedQuery.any { it.contains("未声明的查询参数") }) {
            "契约门禁自检失败：编造的查询参数没有被拦下（扫描器退化）"
        }

        val commentSeparatedQuery = problemsFor(
            """
            interface Sample {
                @GET("$getPath")
                suspend fun sample(@Query /*c*/ ("pcFlag") pcFlag: String? = null): $getResponse
            }
            """.trimIndent()
        )
        check(commentSeparatedQuery.any { it.contains("未声明的查询参数") }) {
            "契约门禁自检失败：注释分隔的 @Query 绕过了参数解析"
        }

        val nonContractReturn = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun wrongReturn(@Body request: $postRequest): String
            }
            """.trimIndent()
        )
        check(nonContractReturn.any { it.contains("返回类型与契约对不上") }) {
            "契约门禁自检失败：把返回类型写成非契约类型（String）没有被拦下"
        }

        val listWrappedReturn = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun wrongReturn(@Body request: $postRequest): List<$postResponse>
            }
            """.trimIndent()
        )
        check(listWrappedReturn.any { it.contains("返回类型与契约对不上") }) {
            "契约门禁自检失败：返回类型多包一层 List<> 没有被拦下"
        }

        val listWrappedBody = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun wrongBody(@Body request: List<$postRequest>): $postResponse
            }
            """.trimIndent()
        )
        check(listWrappedBody.any { it.contains("@Body 类型与契约对不上") }) {
            "契约门禁自检失败：请求体多包一层 List<> 没有被拦下"
        }

        val wrongBodyType = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun wrongBody(@Body request: $getResponse): $postResponse
            }
            """.trimIndent()
        )
        check(wrongBodyType.any { it.contains("@Body 类型与契约对不上") }) {
            "契约门禁自检失败：把 @Body 类型换成别的契约类型没有被拦下"
        }

        val missingBody = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun missingBody(): $postResponse
            }
            """.trimIndent()
        )
        check(missingBody.any { it.contains("但方法没有 @Body") }) {
            "契约门禁自检失败：契约有请求体而方法没有 @Body 没有被拦下"
        }

        val emptyBodyAnnotation = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun emptyParens(@Body() request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(emptyBodyAnnotation.isEmpty()) { "契约门禁自检失败：@Body() 的合法写法被误报：$emptyBodyAnnotation" }

        val stringOnly = problemsFor(
            """
            class Sample {
                fun assertShape(text: String): Boolean = text.contains("@GET(\"pc/whatever\")")
            }
            """.trimIndent()
        )
        check(stringOnly.isEmpty()) { "契约门禁自检失败：字符串字面量里的注解文本被误报：$stringOnly" }
    } finally {
        root.deleteRecursively()
    }
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
            // 先自检扫描器（毫秒级，走真实文件扫描入口），再扫真实源码。
            verifyContractEndpointGuardSelfTest(contractFile, loadContractOperations(contractFile))
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
