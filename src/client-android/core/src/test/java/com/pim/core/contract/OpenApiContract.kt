package com.pim.core.contract

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 仓库布局定位。测试从 Gradle 模块目录（`src/client-android/core`）出发向上查找仓库根，
 * 避免依赖调用方 cwd。
 */
object RepoLayout {
    fun root(start: File = File("").canonicalFile): File {
        var current: File? = start
        while (current != null) {
            if (File(current, "contract/openapi.json").isFile &&
                File(current, ".github/workflows/ci.yml").isFile
            ) {
                return current
            }
            current = current.parentFile
        }
        error("找不到仓库根目录：从 $start 向上没有同时包含 contract/openapi.json 与 .github/workflows/ci.yml 的目录")
    }

    fun contractFile(): File = File(root(), "contract/openapi.json")

    fun workflow(name: String): File = File(root(), ".github/workflows/$name")
}

/**
 * `contract/openapi.json` 的只读视图：只解析校验需要的部分（operation 的路径/方法/
 * query 与 path 参数、requestBody 的 schema 引用与必填性、components.schemas 的属性集）。
 *
 * 有意不解析 responses / securitySchemes —— 本仓快照里它们不存在（响应只有 `200: OK`，
 * 无 securitySchemes），响应与鉴权维度按审计口径保持 NOT-VERIFIED，不在这里伪造。
 */
class OpenApiContract private constructor(private val doc: JsonObject) {
    private val paths: JsonObject = doc.getValue("paths").jsonObject
    private val schemas: JsonObject =
        doc.getValue("components").jsonObject.getValue("schemas").jsonObject

    data class Operation(
        val path: String,
        val method: String,
        val queryParams: Set<String>,
        val pathParams: Set<String>,
        val requestSchemaName: String?,
        val requestBodyRequired: Boolean,
        val hasRequestBody: Boolean,
    )

    val declaredPathCount: Int get() = paths.size

    fun hasPath(path: String): Boolean = paths.containsKey(path)

    fun operation(path: String, method: String): Operation? {
        val item = paths[path]?.jsonObject ?: return null
        val op = item[method.lowercase()]?.jsonObject ?: return null
        val parameters = op["parameters"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["in"]?.jsonPrimitive?.content == "query" || it["in"]?.jsonPrimitive?.content == "path" }
        val query = parameters.filter { it["in"]?.jsonPrimitive?.content == "query" }
            .mapNotNull { it["name"]?.jsonPrimitive?.content }.toSet()
        val pathParams = parameters.filter { it["in"]?.jsonPrimitive?.content == "path" }
            .mapNotNull { it["name"]?.jsonPrimitive?.content }.toSet()
        val requestBody = op["requestBody"]?.jsonObject
        val schema = requestBody?.get("content")?.jsonObject
            ?.get("application/json")?.jsonObject
            ?.get("schema")?.jsonObject
        val ref = schema?.get("\$ref")?.jsonPrimitive?.content
        return Operation(
            path = path,
            method = method.uppercase(),
            queryParams = query,
            pathParams = pathParams,
            requestSchemaName = ref?.substringAfterLast('/'),
            requestBodyRequired = requestBody?.get("required")?.jsonPrimitive?.content == "true",
            hasRequestBody = requestBody != null,
        )
    }

    /**
     * 组件 schema 的属性名集合；schema 不存在时返回 null（调用方据此报「契约组件缺失」而非静默通过）。
     */
    fun schemaPropertyNames(name: String): Set<String>? =
        schemas[name]?.jsonObject?.get("properties")?.jsonObject?.keys?.toSet()

    /** 契约属性 schema 的可判定信息（type / nullable / enum / $ref 组件名）；用于 I-1 的类型与枚举核对。 */
    data class PropertySchema(
        val name: String,
        val typeName: String?,
        val format: String?,
        val nullable: Boolean,
        val enumValues: Set<String>?,
        val refName: String?,
        val hasInfo: Boolean,
    )

    fun schemaProperties(name: String): Map<String, PropertySchema>? {
        val properties = schemas[name]?.jsonObject?.get("properties")?.jsonObject ?: return null
        return properties.mapValues { (propertyName, raw) -> propertySchema(propertyName, raw.jsonObject) }
    }

    fun schemaRequired(name: String): Set<String> =
        schemas[name]?.jsonObject?.get("required")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()

    private fun propertySchema(name: String, raw: JsonObject): PropertySchema {
        val ref = raw["\$ref"]?.jsonPrimitive?.contentOrNull
        val resolved = ref?.let { schemas[it.substringAfterLast('/')]?.jsonObject }
        val source = resolved ?: raw
        val declaredType = source["type"]
        val typeNames = when (declaredType) {
            null -> emptyList()
            else -> (declaredType as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOfNotNull(declaredType.jsonPrimitive.contentOrNull)
        }
        val enumValues = source["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
        val primaryType = typeNames.firstOrNull { it != "null" }
        val nullable = typeNames.contains("null") || source["nullable"]?.jsonPrimitive?.contentOrNull == "true"
        return PropertySchema(
            name = name,
            typeName = primaryType,
            format = source["format"]?.jsonPrimitive?.contentOrNull,
            nullable = nullable,
            enumValues = enumValues,
            refName = ref?.substringAfterLast('/'),
            hasInfo = primaryType != null || enumValues != null || ref != null,
        )
    }

    fun hasSchema(name: String): Boolean = schemas.containsKey(name)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(file: File = RepoLayout.contractFile()): OpenApiContract {
            require(file.isFile) { "契约快照不存在：$file" }
            val element = json.parseToJsonElement(file.readText())
            return OpenApiContract(element.jsonObject)
        }
    }
}
