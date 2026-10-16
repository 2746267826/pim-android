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
    Regex("""@(?:[\w.]+\.)?(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|HTTP)\b""")

/** 与 [RETROFIT_ANNOTATION_PREFIX] 同一口径：允许 `@retrofit2.http.GET("x")` 这类全限定写法。 */
private fun annotationPattern(name: String): Regex = Regex("""@(?:[\w.]+\.)?$name\s*\(""")

/** 契约 2xx 不是 JSON 时允许的返回类型（Retrofit 原样把响应体交回来的载体）。 */
private val NON_JSON_RESPONSE_TYPES = setOf("ResponseBody", "Unit")

/** 契约请求体不是 JSON 时允许的 `@Body` 类型。 */
private val NON_JSON_REQUEST_TYPES = setOf("RequestBody")

/**
 * 把「参数名/请求体形态」交给运行时的注解：门禁无法核对它们会不会传出契约外的东西，
 * 因此一律拒绝（真需要就扩展本门禁，而不是绕过）。
 */
private val REJECTED_RETROFIT_ANNOTATIONS = mapOf(
    "QueryMap" to "@QueryMap 可以发送契约里没有的任意查询键，门禁无法逐个核对；请改成逐个 @Query。",
    "QueryName" to "@QueryName 会把参数值当成查询键，门禁无法核对键名；请改成显式 @Query。",
    "Field" to "@Field 发送表单字段，契约里没有对应声明。",
    "FieldMap" to "@FieldMap 可以发送契约里没有的任意表单字段。",
    "FormUrlEncoded" to "@FormUrlEncoded 表单体与契约的 JSON 请求体不一致。",
    "Multipart" to "@Multipart 与契约声明的请求体不一致。",
    "Part" to "@Part 与契约声明的请求体不一致。",
    "PartMap" to "@PartMap 可以发送契约里没有的任意 part。",
    "HeaderMap" to "@HeaderMap 可以发送契约里没有的任意请求头。"
)

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
    val byKey: Map<String, ContractOperation>
)

/** 客户端 operation 清单：与 scripts/contract/generate-kotlin.py 用的是同一份。 */
private fun clientOperationsFile(contractFile: File): File =
    contractFile.parentFile.parentFile.resolve("scripts/contract/client-operations.json")

@Suppress("UNCHECKED_CAST")
private fun loadClientOperations(operationsFile: File): Set<String> {
    if (!operationsFile.isFile) {
        throw GradleException("客户端 operation 清单不存在：$operationsFile")
    }
    val root = groovy.json.JsonSlurper().parse(operationsFile) as? Map<String, Any?>
        ?: throw GradleException("客户端 operation 清单不是 JSON 对象：$operationsFile")
    val operations = root["operations"] as? List<*> ?: throw GradleException("清单缺少 operations：$operationsFile")
    return operations.mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val method = (map["method"] as? String)?.uppercase() ?: return@mapNotNull null
        val path = map["path"] as? String ?: return@mapNotNull null
        "$method $path"
    }.toSet()
}

/** 生成物路径：与 scripts/contract/generate-kotlin.py 的输出一致。 */
private fun generatedModelsFile(contractFile: File): File =
    contractFile.parentFile.parentFile.resolve(
        "src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt"
    )

/**
 * 生成物里实际声明了哪些契约类型（闭包内的 99 个）。
 *
 * 注意**不能**拿契约的全部 `components.schemas`（566 个）当集合：客户端本地类只要恰好和某个
 * 未接入的契约 schema 同名（例如 `DeviceLivenessSummary`），就会被误判成"顶替契约类型"。
 * 门禁只该管"真的生成进客户端的类型"。
 */
private fun loadGeneratedTypeNames(modelsFile: File): Set<String> {
    if (!modelsFile.isFile) {
        throw GradleException(
            "契约生成物不存在：$modelsFile（请运行 bash scripts/ci/generate-contract-kotlin.sh）"
        )
    }
    val declarations = Regex(
        """^(?:data\s+class|enum\s+class|value\s+class|sealed\s+interface|interface|typealias)\s+(\w+)""",
        RegexOption.MULTILINE
    )
    val names = declarations.findAll(modelsFile.readText()).map { it.groupValues[1] }.toSet()
    if (names.isEmpty()) {
        throw GradleException("契约生成物里没有解析出任何类型：$modelsFile")
    }
    return names
}

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
    /** 解析阶段就发现的问题（例如注解参数不是单一字符串字面量）。 */
    val argumentProblems: List<String>,
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
    return ContractOperations(operations)
}

/**
 * 把注释与字符串字面量**等长**替换成空格（保留换行），用于结构扫描。
 *
 * 为什么要这样：测试/文档里会出现 `"@GET(\"mobile/summary\")"` 这种**字符串里的注解文本**，
 * 直接扫原文会把它们当成真注解；反过来，`@POST /*c*/ ("x")` 这种**注释分隔**的写法又必须被看见。
 * 掩码后偏移量与原文一一对应：结构从掩码读，参数值再从原文取，两边都不丢。
 */
private data class MaskedSource(
    /** 等长掩码文本：注释与字符串字面量内容都变成空格，其余原样。 */
    val text: String,
    /** **真实**字符串字面量的内容区间（注释里的引号不算），用于从原文取值。 */
    val stringLiterals: List<IntRange>
)

private fun maskSource(input: String): MaskedSource {
    val out = StringBuilder(input.length)
    val literals = mutableListOf<IntRange>()
    var i = 0
    fun blank(ch: Char) { out.append(if (ch == '\n') '\n' else ' ') }
    while (i < input.length) {
        val c = input[i]
        when {
            c == '/' && i + 1 < input.length && input[i + 1] == '/' -> {
                while (i < input.length && input[i] != '\n') { blank(input[i]); i++ }
            }
            c == '/' && i + 1 < input.length && input[i + 1] == '*' -> {
                // Kotlin 允许块注释嵌套：必须计深度，否则外层注释会被提前结束，
                // 后面的真实注解会被吞成字符串（Codex review 构造过这种写法）。
                var depth = 0
                while (i < input.length) {
                    if (input[i] == '/' && i + 1 < input.length && input[i + 1] == '*') {
                        depth++
                        blank(input[i]); blank(input[i + 1]); i += 2
                    } else if (input[i] == '*' && i + 1 < input.length && input[i + 1] == '/') {
                        depth--
                        blank(input[i]); blank(input[i + 1]); i += 2
                        if (depth == 0) break
                    } else {
                        blank(input[i]); i++
                    }
                }
            }
            c == '"' && input.startsWith("\"\"\"", i) -> {
                repeat(3) { blank(' '); i++ }
                val start = i
                while (i < input.length && !input.startsWith("\"\"\"", i)) { blank(input[i]); i++ }
                if (i > start) literals += start until i
                if (i < input.length) { repeat(3) { blank(' '); i++ } }
            }
            c == '"' -> {
                blank(' '); i++
                val start = i
                while (i < input.length && input[i] != '"' && input[i] != '\n') {
                    if (input[i] == '\\' && i + 1 < input.length) { blank(' '); blank(' '); i += 2 } else { blank(input[i]); i++ }
                }
                if (i > start) literals += start until i
                if (i < input.length && input[i] == '"') { blank(' '); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return MaskedSource(out.toString(), literals)
}

/** 取区间内**第一个真实字符串字面量**的值（注释里的引号不是字面量，因此不会被误读）。 */
private fun firstStringLiteral(source: MaskedSource, original: String, from: Int, to: Int): String? {
    val range = source.stringLiterals.firstOrNull { it.first in from until to } ?: return null
    return original.substring(range.first, range.last + 1)
}

/**
 * 注解参数是不是「单一字符串字面量」。
 *
 * `@POST("auth/login" + "/invented")` 这种拼接只看第一个字面量会看错端点，因此：
 * 去掉第一个字面量（含引号）与可选的 `name =` 前缀后，剩下的掩码文本里不允许再出现 `+` 或别的字面量。
 */
private fun isSingleStringLiteralArgument(
    source: MaskedSource,
    maskedParenContent: String,
    parenStart: Int
): Boolean {
    if (maskedParenContent.isEmpty()) return true
    val contentEnd = parenStart + maskedParenContent.length
    val literal = source.stringLiterals.firstOrNull { it.first in parenStart until contentEnd }
        ?: return false
    val extraLiterals = source.stringLiterals.count { it.first in (literal.last + 2) until contentEnd }
    val localStart = (literal.first - 1 - parenStart).coerceIn(0, maskedParenContent.length)
    val localEnd = (literal.last + 1 - parenStart).coerceIn(-1, maskedParenContent.length - 1)
    if (localEnd + 1 < localStart) return false
    val remainder = maskedParenContent.removeRange(localStart, localEnd + 1)
        .replace(Regex("""^\s*\w+\s*=\s*"""), "")
    return !remainder.contains('+') && !remainder.contains('(') && !remainder.contains(')') &&
        extraLiterals == 0
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
private fun annotationStringValues(
    source: MaskedSource,
    original: String,
    annotationName: String,
    from: Int,
    to: Int
): List<Pair<String, Boolean>> {
    val values = mutableListOf<Pair<String, Boolean>>()
    val masked = source.text
    val regex = annotationPattern(annotationName)
    for (match in regex.findAll(masked, from)) {
        if (match.range.first >= to) break
        val openParen = masked.indexOf('(', match.range.first)
        if (openParen < 0 || openParen >= to) continue
        val closeParen = matchingParen(masked, openParen)
        val value = firstStringLiteral(source, original, openParen + 1, closeParen) ?: continue
        val single = isSingleStringLiteralArgument(
            source, masked.substring(openParen + 1, closeParen), openParen + 1
        )
        values += value to single
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
private fun findRetrofitCalls(source: MaskedSource, original: String, schemaNames: Set<String>): List<RetrofitCall> {
    val masked = source.text
    val calls = mutableListOf<RetrofitCall>()
    val annotationHeader = Regex("""@(?:[\w.]+\.)?(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\(""")
    for (match in annotationHeader.findAll(masked)) {
        val annotationOpen = masked.indexOf('(', match.range.first)
        val annotationClose = matchingParen(masked, annotationOpen)
        val afterAnnotation = (annotationClose + 1).coerceAtMost(masked.length)
        val funMatch = Regex("""\bfun\s+(\w+)\s*\(""").find(masked, afterAnnotation) ?: continue
        // 注解后面紧跟的必须是同一个函数声明：中间再出现注解说明这是另一个方法。
        val between = masked.substring(afterAnnotation, funMatch.range.first)
        if (between.contains(Regex("""@(?:[\w.]+\.)?(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\("""))) continue

        val openParen = masked.indexOf('(', funMatch.range.first)
        val closeParen = matchingParen(masked, openParen)
        // 端点取「注解括号内第一个**真实**字符串字面量」：注释里的 `/* "x" */` 不会被误读。
        val rawPath = firstStringLiteral(source, original, annotationOpen + 1, annotationClose) ?: continue
        val parameters = original.substring(openParen + 1, minOf(closeParen, original.length))
        val maskedParameters = masked.substring(openParen + 1, minOf(closeParen, masked.length))
        val returnType = extractReturnType(masked, closeParen)
        val bodyType = Regex("""@(?:[\w.]+\.)?Body\s*(?:\(\s*\))?\s+\w+\s*:\s*([^,)=]+)""")
            .find(maskedParameters)?.groupValues?.get(1)?.trim()
        val argumentProblems = mutableListOf<String>()
        if (!isSingleStringLiteralArgument(
                source, masked.substring(annotationOpen + 1, annotationClose), annotationOpen + 1
            )
        ) {
            argumentProblems += "端点不是单一字符串字面量（拼接/多字面量无法核对）"
        }
        val pathValues = annotationStringValues(source, original, "Path", openParen + 1, closeParen)
        val queryValues = annotationStringValues(source, original, "Query", openParen + 1, closeParen)
        if (pathValues.any { !it.second }) argumentProblems += "@Path 不是单一字符串字面量（拼接/多字面量无法核对）"
        if (queryValues.any { !it.second }) argumentProblems += "@Query 不是单一字符串字面量（拼接/多字面量无法核对）"
        calls += RetrofitCall(
            method = match.groupValues[1],
            rawPath = rawPath,
            functionName = funMatch.groupValues[1],
            pathVariables = pathValues.map { it.first }.toSet(),
            queryParameters = queryValues.map { it.first },
            bodyType = bodyType,
            returnType = normalizeType(returnType),
            referencedSchemaNames = contractNamesIn(maskedParameters + " " + returnType, schemaNames),
            maskedParameters = maskedParameters,
            argumentProblems = argumentProblems,
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
    if (Regex("""@(?:[\w.]+\.)?HTTP\b""").containsMatchIn(masked)) {
        problems += "$relativeFile 使用了 @HTTP(...)：本门禁无法把自由形态的 method/path 与契约核对。" +
            "请改用 @GET/@POST/@PUT/@DELETE/@PATCH（要新形态请先扩展 verifyContractEndpoints）。"
    }
    if (Regex("""@(?:[\w.]+\.)?Url\b""").containsMatchIn(masked)) {
        problems += "$relativeFile 使用了 @Url：动态 URL 无法在编译期核对契约，等于绕开接口门禁。"
    }
    for ((name, why) in REJECTED_RETROFIT_ANNOTATIONS) {
        if (Regex("""@(?:[\w.]+\.)?$name\b""").containsMatchIn(masked)) {
            problems += "$relativeFile 使用了 @$name：$why"
        }
    }
    for (name in RETROFIT_ANNOTATIONS) {
        val total = Regex("""@(?:[\w.]+\.)?$name\b""").findAll(masked).count()
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
        val total = Regex("""@(?:[\w.]+\.)?$name\b""").findAll(maskedParameters).count()
        val parsed = if (name == "Path") call.pathVariables.size else call.queryParameters.size
        if (total != parsed) {
            problems += "$location 有 $total 处 @$name，但只解析出 $parsed 处（写法超出本门禁的解析范围）。"
        }
    }
    val bodyAnnotations = Regex("""@(?:[\w.]+\.)?Body\b""").findAll(maskedParameters).count()
    if (bodyAnnotations > 0 && !call.hasBody) {
        problems += "$location 出现 @Body 但解析不出参数类型（写法超出本门禁的解析范围）。"
    }
    if (bodyAnnotations > 1) {
        problems += "$location 有 $bodyAnnotations 处 @Body：一个方法只能有一个请求体。"
    }
}

/**
 * 契约类型名不能被「本地别名/本地类」顶替。
 *
 * 文本扫描认的是类型**名字**，所以 `typealias LoginRequest = List<...>` 这种写法会让
 * 「@Body 是 LoginRequest」看起来成立、实际结构却是另一个东西。既然门禁不是编译器，
 * 就把这种同名顶替一律拒掉（生成文件自身的 `typealias PimHealthStatus = Int` 除外）。
 */
private fun verifyContractTypeNameShadowing(
    relativeFile: String,
    masked: String,
    schemaNames: Set<String>,
    problems: MutableList<String>
) {
    if (relativeFile.replace('\\', '/').contains("com/pim/core/contract/")) return
    val declared = mutableListOf<Pair<String, String>>()
    Regex("""\btypealias\s+`?(\w+)`?""").findAll(masked).forEach {
        declared += it.groupValues[1] to "typealias"
    }
    Regex("""\bimport\s+[\w.]*?(\w+)\s+as\s+(\w+)""").findAll(masked).forEach {
        declared += it.groupValues[2] to "import ... as"
    }
    Regex("""\b(?:data\s+|sealed\s+|abstract\s+|open\s+)?class\s+`?(\w+)`?""").findAll(masked).forEach {
        declared += it.groupValues[1] to "class"
    }
    Regex("""\binterface\s+`?(\w+)`?""").findAll(masked).forEach {
        declared += it.groupValues[1] to "interface"
    }
    // object（含 companion object 之外的顶层/嵌套 object）同样能顶替类型名，顺手覆盖。
    Regex("""\bobject\s+`?(\w+)`?""").findAll(masked).forEach {
        declared += it.groupValues[1] to "object"
    }
    if (Regex("""\bimport\s+retrofit2\.http\.\w+\s+as\s+`?\w+`?""").containsMatchIn(masked)) {
        problems += "$relativeFile 把 retrofit2 注解改名导入（import retrofit2.http.X as Y）：" +
            "改名后的注解本门禁看不见，等于绕开接口门禁。请用原名导入。"
    }
    declared.filter { it.first in schemaNames }
        .distinct()
        .forEach { (name, kind) ->
            problems += "$relativeFile 用本地 $kind 顶替了契约类型名 $name：文本门禁认的是类型名，" +
                "同名顶替会让「类型与契约一致」的检查失效。请改用生成类型本身（或换个名字）。"
        }
}

private fun verifyRetrofitCalls(
    relativeFile: String,
    call: RetrofitCall,
    operations: Map<String, ContractOperation>,
    problems: MutableList<String>
) {
    val location = "$relativeFile:${call.line} (${call.functionName})"
    call.argumentProblems.forEach { problems += "$location $it。" }
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
    val generatedTypes = loadGeneratedTypeNames(generatedModelsFile(contractFile))
    val problems = mutableListOf<String>()

    // 客户端清单里的 operation 必须在契约里存在，且它的类型必须在生成物里：
    // 前者拦住「清单里编造端点」，后者拦住「契约改了但没重新生成」（与 CI 门禁双保险）。
    loadClientOperations(clientOperationsFile(contractFile)).sorted().forEach { key ->
        val operation = contract.byKey[key]
        if (operation == null) {
            problems += "scripts/contract/client-operations.json 里的 $key 在契约里不存在：" +
                "需要新接口请先让后端在 pim-api 实现并刷新契约快照。"
            return@forEach
        }
        val expected = listOfNotNull(operation.requestSchema) + operation.responseSchemas
        val missing = expected.filterNot { it in generatedTypes }
        if (missing.isNotEmpty()) {
            problems += "契约生成物缺少 $key 的类型 ${missing.sorted()}：" +
                "请运行 bash scripts/ci/generate-contract-kotlin.sh 重新生成并提交。"
        }
    }

    if (!sourcesRoot.isDirectory) return problems
    sourcesRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .forEach { file ->
            val original = file.readText()
            val source = maskSource(original)
            val masked = source.text
            val relative = file.relativeTo(sourcesRoot).path
            // 同名顶替检查对**每个文件**都要做（顶替类型名的文件自己可能一个注解都没有）。
            verifyContractTypeNameShadowing(relative, masked, generatedTypes, problems)
            // 预筛选走**掩码后**的文本：注释分隔写法（`@POST /*c*/ ("x")`）与 `@HTTP(...)` 都必须被看见。
            if (!RETROFIT_ANNOTATION_PREFIX.containsMatchIn(masked)) {
                return@forEach
            }
            val calls = findRetrofitCalls(source, original, generatedTypes)
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
    //
    // 还必须挑**请求/响应类型真的已生成进本仓**的 operation：契约快照是全量上游契约（含大量本客户端
    // 没接入的 operation，例如 `/api/v1/admin/users` 的 `CreateUserRequest`）。拿它们当样例会让
    // "本地同名 typealias 顶替契约类型"这条自检失效 —— 扫描器只把**本地生成过的类型名**当契约类型，
    // 样例不在其中时它不会报"顶替"，自检便误判为"没有被拦下"（pim-android#21 刷新快照后真踩到）。
    // 判定强度不变：样例仍来自契约里真实存在的 operation，仍走真实文件扫描入口。
    val generatedTypes = loadGeneratedTypeNames(generatedModelsFile(contractFile))

    val sampleGet = contract.byKey.entries.firstOrNull { entry ->
        entry.key.startsWith("GET ") && entry.value.responseSchemas.isNotEmpty() &&
            !entry.value.path.contains("{") &&
            entry.value.responseSchemas.all { it in generatedTypes }
    } ?: throw GradleException(
        "契约门禁自检失败：契约里没有响应类型已在本地生成物里的、无路径参数的 GET operation"
    )
    val samplePost = contract.byKey.entries.firstOrNull { entry ->
        val requestSchema = entry.value.requestSchema
        entry.key.startsWith("POST ") && requestSchema != null &&
            entry.value.responseSchemas.isNotEmpty() && !entry.value.path.contains("{") &&
            requestSchema in generatedTypes &&
            entry.value.responseSchemas.all { it in generatedTypes }
    } ?: throw GradleException(
        "契约门禁自检失败：契约里没有请求/响应类型都已在本地生成物里的、无路径参数的 POST operation"
    )

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

        val commentEmbeddedString = problemsFor(
            """
            interface Sample {
                @POST(/* "$postPath" */ "pc/whatever")
                suspend fun fabricated(@Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(commentEmbeddedString.any { it.contains("不存在的端点") }) {
            "契约门禁自检失败：注解括号里注释中的字符串被当成了端点值"
        }

        val commentEmbeddedQueryString = problemsFor(
            """
            interface Sample {
                @GET("$getPath")
                suspend fun sample(@Query(/* "x" */ "pcFlag") pcFlag: String? = null): $getResponse
            }
            """.trimIndent()
        )
        check(commentEmbeddedQueryString.any { it.contains("未声明的查询参数") }) {
            "契约门禁自检失败：@Query 括号里注释中的字符串被当成了参数名"
        }

        val shadowedTypeName = problemsFor(
            """
            typealias $postRequest = List<com.pim.core.contract.$postRequest>

            interface Sample {
                @POST("$postPath")
                suspend fun sample(@Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(shadowedTypeName.any { it.contains("顶替了契约类型名") }) {
            "契约门禁自检失败：本地同名 typealias 顶替契约类型没有被拦下"
        }

        val qualifiedAnnotation = problemsFor(
            """
            interface Sample {
                @retrofit2.http.POST("pc/whatever")
                suspend fun fabricated(@retrofit2.http.Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(qualifiedAnnotation.any { it.contains("不存在的端点") }) {
            "契约门禁自检失败：全限定注解写法（@retrofit2.http.POST(...)）绕过了扫描器"
        }

        val renamedAnnotationImport = problemsFor(
            """
            import retrofit2.http.POST as HttpPost

            interface Sample {
                @HttpPost("pc/whatever")
                suspend fun fabricated(): Unit
            }
            """.trimIndent()
        )
        check(renamedAnnotationImport.any { it.contains("改名导入") }) {
            "契约门禁自检失败：把 Retrofit 注解改名导入（import ... as ...）绕过了扫描器"
        }

        val queryMap = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun sample(@QueryMap flags: Map<String, String>, @Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(queryMap.any { it.contains("@QueryMap") }) {
            "契约门禁自检失败：@QueryMap 可以发送任意契约外查询键，必须被拦下"
        }

        val concatenatedEndpoint = problemsFor(
            """
            interface Sample {
                @POST("$postPath" + "/invented")
                suspend fun sample(@Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(concatenatedEndpoint.any { it.contains("不是单一字符串字面量") }) {
            "契约门禁自检失败：端点用字面量拼接（@POST(\"a\" + \"b\")）没有被拦下"
        }

        val concatenatedQuery = problemsFor(
            """
            interface Sample {
                @GET("$getPath")
                suspend fun sample(@Query("start" + "Flag") flag: String? = null): $getResponse
            }
            """.trimIndent()
        )
        check(concatenatedQuery.any { it.contains("不是单一字符串字面量") }) {
            "契约门禁自检失败：@Query 用字面量拼接没有被拦下"
        }

        val backtickedAlias = problemsFor(
            """
            typealias `$postRequest` = List<com.pim.core.contract.$postRequest>

            interface Sample {
                @POST("$postPath")
                suspend fun sample(@Body request: `$postRequest`): $postResponse
            }
            """.trimIndent()
        )
        check(backtickedAlias.any { it.contains("顶替了契约类型名") }) {
            "契约门禁自检失败：反引号声明的同名 typealias 绕过了顶替检查"
        }

        val nestedComment = problemsFor(
            """
            /* outer /* inner */ " */ @POST("pc/whatever")
            suspend fun fabricated(): Unit
            """.trimIndent()
        )
        check(nestedComment.any { it.contains("不存在的端点") }) {
            "契约门禁自检失败：嵌套块注释写法让真实注解被吞掉/被跳过"
        }

        val qualifiedQuery = problemsFor(
            """
            interface Sample {
                @POST("$postPath")
                suspend fun sample(
                    @retrofit2.http.Query("pcFlag") flag: String? = null,
                    @Body request: $postRequest
                ): $postResponse
            }
            """.trimIndent()
        )
        check(qualifiedQuery.any { it.contains("未声明的查询参数") }) {
            "契约门禁自检失败：全限定 @retrofit2.http.Query 没有被识别"
        }

        val shadowingObject = problemsFor(
            """
            object $postRequest {
                val payload: String = "x"
            }
            """.trimIndent()
        )
        check(shadowingObject.any { it.contains("顶替了契约类型名") }) {
            "契约门禁自检失败：本地 object 同名顶替契约类型没有被拦下"
        }

        val bareAnnotation = problemsFor(
            """
            interface Sample {
                @retrofit2.http.POST
                suspend fun probe(
                    @retrofit2.http.Url url: String,
                    @retrofit2.http.Body request: $postRequest
                ): $postResponse
            }
            """.trimIndent()
        )
        check(bareAnnotation.any { it.contains("@Url") }) {
            "契约门禁自检失败：无括号的 HTTP 注解（@POST 省略 value）让整文件跳过检查"
        }

        val parenthesisedArgument = problemsFor(
            """
            interface Sample {
                @POST(("pc/whatever"))
                suspend fun probe(@Body request: $postRequest): $postResponse
            }
            """.trimIndent()
        )
        check(parenthesisedArgument.any { it.contains("不是单一字符串字面量") }) {
            "契约门禁自检失败：注解参数被括号包裹（@POST((\"x\"))）没有被拦下"
        }

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
