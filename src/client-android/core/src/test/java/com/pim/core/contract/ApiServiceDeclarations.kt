package com.pim.core.contract

import com.pim.core.settings.PimServerEndpoints
import java.io.File
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 用反射读出 Retrofit 接口的**真实注解**（不是源码字符串匹配），得到声明侧的操作集合。
 *
 * 路径解析与运行期一致：`ApiClientProvider` 用 `PimServerEndpoints.apiBaseUrl`
 * （即 `{origin}/api/v1/`）作为 Retrofit baseUrl，因此相对路径会带上 `/api/v1` 前缀；
 * 以 `/` 开头的绝对路径按 Retrofit 语义直接使用。
 */
object ApiServiceDeclarations {

    /** 与运行期一致的 base path，直接从生产代码取，避免测试里硬编码。 */
    val BASE_PATH: String =
        PimServerEndpoints.from("http://contract-probe.invalid/api/v1").apiBaseUrl.encodedPath
            .trimEnd('/')

    data class Declared(
        val functionName: String,
        val httpMethod: String,
        val declaredPath: String,
        val path: String,
        val queryParams: List<String>,
        val pathParams: List<String>,
        val bodyType: Class<*>?,
    ) {
        val hasBody: Boolean get() = bodyType != null
        val isRawBody: Boolean get() = bodyType == RequestBody::class.java

        val label: String get() = "$httpMethod $path ($functionName)"
    }

    private val VERBS: List<Pair<Class<out Annotation>, String>> = listOf(
        GET::class.java to "GET",
        POST::class.java to "POST",
        PUT::class.java to "PUT",
        DELETE::class.java to "DELETE",
        PATCH::class.java to "PATCH",
    )

    fun scan(iface: Class<*>): List<Declared> =
        iface.methods.mapNotNull { method ->
            val http = VERBS.firstNotNullOfOrNull { (annotation, verb) ->
                method.getAnnotation(annotation)?.let { verb to annotationValue(it) }
            } ?: return@mapNotNull null

            val (verb, declaredPath) = http
            val queryParams = mutableListOf<String>()
            val pathParams = mutableListOf<String>()
            var bodyType: Class<*>? = null
            method.parameterAnnotations.forEachIndexed { index, annotations ->
                for (annotation in annotations) {
                    when (annotation) {
                        is Query -> queryParams += annotation.value
                        is Path -> pathParams += annotation.value
                        is Body -> bodyType = method.parameterTypes[index]
                    }
                }
            }
            Declared(
                functionName = method.name,
                httpMethod = verb,
                declaredPath = declaredPath,
                path = qualify(declaredPath),
                queryParams = queryParams,
                pathParams = pathParams,
                bodyType = bodyType,
            )
        }.sortedBy { it.label }

    /** Retrofit 语义：`/x` 是相对 host 的绝对路径，其余拼在 baseUrl 之后。 */
    fun qualify(declaredPath: String): String =
        if (declaredPath.startsWith("/")) declaredPath else "$BASE_PATH/${declaredPath.trimStart('/')}"

    private fun annotationValue(annotation: Annotation): String = when (annotation) {
        is GET -> annotation.value
        is POST -> annotation.value
        is PUT -> annotation.value
        is DELETE -> annotation.value
        is PATCH -> annotation.value
        else -> error("unsupported annotation ${annotation.annotationClass}")
    }
}

/** 源码侧（独立机制）解析出的一个声明。 */
data class SourceDeclaration(val functionName: String, val httpMethod: String, val declaredPath: String)

/**
 * **独立机制**的声明枚举：不经过反射，直接从 `ApiService.kt` 源码里解析
 * `@VERB("path")` + 紧随其后的 `fun name`。
 *
 * 用途（C-4 修正）：`ApiContractConformanceTest` 不再用固定魔数（34）当哨兵 ——
 * 固定总数既会拦住合法的端点删除（F-B-03 已删除 `uploadStats`），又发现不了
 * 「反射扫描悄悄漏读一个方法」以外的漂移。改为两种**互相独立**的机制全量对账：
 * 反射读运行期注解 vs 源码文本解析；两者必须逐条一致。
 *
 * RED-4 补充：反射侧的「已知抽样方法」守卫（在 `ApiContractConformanceTest` 里）只能取
 * **不在删除计划内**的稳定方法；合法删除（如 F-B-03 的 `uploadStats`）由本对账机制的
 * 「两侧同向变化」保证，不再依赖任何固定名单。
 */
object ApiServiceSourceScan {

    val SOURCE_PATH = "src/client-android/core/src/main/java/com/pim/core/network/ApiService.kt"

    private val DECLARATION = Regex(
        "@(GET|POST|PUT|DELETE|PATCH)\\(\"([^\"]*)\"\\)\\s+(?:suspend\\s+)?fun\\s+(\\w+)",
    )

    fun sourceFile(root: File = RepoLayout.root()): File {
        val file = File(root, SOURCE_PATH)
        require(file.isFile) { "找不到 ApiService 源码：$file" }
        return file
    }

    fun scan(file: File): List<SourceDeclaration> =
        DECLARATION.findAll(file.readText())
            .map { match ->
                SourceDeclaration(
                    functionName = match.groupValues[3],
                    httpMethod = match.groupValues[1],
                    declaredPath = match.groupValues[2],
                )
            }
            .sortedBy { "${it.httpMethod} ${it.declaredPath} ${it.functionName}" }
            .toList()
}

/**
 * 全量对账：反射扫描结果 vs 源码独立扫描结果。
 * 返回问题列表（空 = 两种机制对同一份声明集合完全一致）。
 */
object DeclarationReconciliation {

    fun check(
        reflected: List<ApiServiceDeclarations.Declared>,
        source: List<SourceDeclaration>,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (source.isEmpty()) {
            return listOf("源码扫描器一个声明都没解析出来（对账机制失效，不能当作通过）")
        }
        fun key(method: String, path: String, function: String) = "$method $path ($function)"

        val reflectedKeys = reflected.map { key(it.httpMethod, it.declaredPath, it.functionName) }.toSet()
        val sourceKeys = source.map { key(it.httpMethod, it.declaredPath, it.functionName) }.toSet()

        val missingInReflection = (sourceKeys - reflectedKeys).sorted()
        val extraInReflection = (reflectedKeys - sourceKeys).sorted()
        if (missingInReflection.isNotEmpty()) {
            problems += "源码里有、反射扫描漏掉的声明：$missingInReflection（反射扫描器失效或注解形式不支持）"
        }
        if (extraInReflection.isNotEmpty()) {
            problems += "反射扫描有、源码里没有的声明：$extraInReflection（源码解析正则漏匹配或扫描器凭空多读）"
        }
        return problems
    }
}
