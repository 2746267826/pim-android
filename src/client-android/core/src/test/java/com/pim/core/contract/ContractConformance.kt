package com.pim.core.contract

import java.lang.reflect.Type
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer

/** 客户端 DTO 字段的可判定信息（真实 kotlinx.serialization 描述符，`@SerialName` 生效后的 wire 名）。 */
data class ClientElement(
    val name: String,
    val kind: ClientKind,
    val nullable: Boolean,
    val optional: Boolean,
    val enumValues: Set<String>?,
) {
    val wireType: String? get() = kind.wireType
}

enum class ClientKind(val wireType: String?) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ENUM("string"),
    ARRAY("array"),
    OBJECT("object"),
    UNKNOWN(null),
}

/** 真实 kotlinx.serialization 序列名（`@SerialName` 生效后的 wire 名）与字段级描述符。 */
object SerialNames {
    @OptIn(ExperimentalSerializationApi::class)
    fun of(type: Class<*>): Set<String> =
        serializer(type as Type).descriptor.elementNames.toSet()

    /** 不是 `@Serializable`（例如 `okhttp3.RequestBody`）时返回 null。 */
    fun ofOrNull(type: Class<*>): Set<String>? = runCatching { of(type) }.getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    fun elementsOf(type: Class<*>): List<ClientElement> {
        val descriptor = serializer(type as Type).descriptor
        return (0 until descriptor.elementsCount).map { index ->
            val element = descriptor.getElementDescriptor(index)
            val kind = when (element.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> ClientKind.STRING
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                    ClientKind.INTEGER
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> ClientKind.NUMBER
                PrimitiveKind.BOOLEAN -> ClientKind.BOOLEAN
                SerialKind.ENUM -> ClientKind.ENUM
                StructureKind.LIST -> ClientKind.ARRAY
                StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> ClientKind.OBJECT
                else -> ClientKind.UNKNOWN
            }
            ClientElement(
                name = descriptor.getElementName(index),
                kind = kind,
                nullable = element.isNullable,
                optional = descriptor.isElementOptional(index),
                enumValues = if (kind == ClientKind.ENUM) element.elementNames.toSet() else null,
            )
        }
    }

    fun elementsOfOrNull(type: Class<*>): List<ClientElement>? =
        runCatching { elementsOf(type) }.getOrNull()
}

/**
 * 契约一致性检查（纯函数，便于故障对照注入）。
 *
 * 判定口径（AC-COM-02 的**可判定**部分，第二轮 I-1 补强后）：
 * - 客户端声明的**路径/方法/query 参数/请求体形状/字段名/字段类型/枚举/可空性/必填性**
 *   不得与契约冲突 → 冲突即违规；
 * - 客户端**少发**契约里的可选参数不算违规（契约未声明 required 时客户端可只发需要的）；
 * - 契约**没有提供**判定所需信息（无 type / 无 enum / 无 required）时记 `NOT-VERIFIED` note，
 *   不判违规、也不假装已覆盖；
 * - query 参数**仅大小写不同**不算违规（F-B-02 判定为契约侧命名风格缺口、本仓不判违规），只记 Note。
 */
object ContractConformance {

    data class Violation(val function: String, val rule: String, val detail: String) {
        override fun toString(): String = "[$rule] $function — $detail"
    }

    data class Note(val function: String, val detail: String)

    data class Report(val violations: List<Violation>, val notes: List<Note>) {
        fun violationsOf(rule: String): List<Violation> = violations.filter { it.rule == rule }

        fun render(): String = buildString {
            appendLine("契约一致性检查：${violations.size} 条违规 / ${notes.size} 条提示")
            violations.forEach { appendLine("  VIOLATION $it") }
            notes.forEach { appendLine("  note      ${it.function}: ${it.detail}") }
        }
    }

    const val RULE_PATH = "R-PATH"
    const val RULE_METHOD = "R-METHOD"
    const val RULE_PATH_PARAM = "R-PATH-PARAM"
    const val RULE_QUERY = "R-QUERY"
    const val RULE_BODY_PRESENCE = "R-BODY-PRESENCE"
    const val RULE_BODY_SCHEMA = "R-BODY-SCHEMA"
    const val RULE_BODY_TYPE = "R-BODY-TYPE"
    const val RULE_BODY_ENUM = "R-BODY-ENUM"
    const val RULE_BODY_NULLABLE = "R-BODY-NULLABLE"
    const val RULE_BODY_REQUIRED = "R-BODY-REQUIRED"

    /**
     * 契约组件名 → 客户端 DTO 名。仅收录「命名风格不同但字段集合已逐项核对一致」的对。
     * 这不是放行通道：每一项都必须有对应的字段级核对（R-BODY-SCHEMA 的字段子集规则仍然生效）。
     */
    val SCHEMA_NAME_ALIASES: Map<String, String> = mapOf(
        "EndpointNotificationActionRequest" to "EndpointNotificationActionRequestDto",
    )

    fun check(
        declared: List<ApiServiceDeclarations.Declared>,
        contract: OpenApiContract,
        serialNames: (Class<*>) -> Set<String>? = SerialNames::ofOrNull,
        elements: (Class<*>) -> List<ClientElement>? = SerialNames::elementsOfOrNull,
    ): Report {
        val violations = mutableListOf<Violation>()
        val notes = mutableListOf<Note>()

        for (operation in declared) {
            val contractOperation = contract.operation(operation.path, operation.httpMethod)
            if (contractOperation == null) {
                val pathExists = contract.hasPath(operation.path)
                val rule = if (pathExists) RULE_METHOD else RULE_PATH
                val detail = if (pathExists) {
                    "契约存在该路径但没有 ${operation.httpMethod} operation"
                } else {
                    "契约中不存在路径 ${operation.path}（声明处：@${operation.httpMethod}(\"${operation.declaredPath}\")）"
                }
                violations += Violation(operation.label, rule, detail)
                continue
            }

            // query 参数：客户端不得声明契约外参数；大小写不同只记 note。
            for (parameter in operation.queryParams) {
                when {
                    contractOperation.queryParams.contains(parameter) -> Unit
                    contractOperation.queryParams.any { it.equals(parameter, ignoreCase = true) } ->
                        notes += Note(
                            operation.label,
                            "query 参数 $parameter 与契约同名但大小写不同" +
                                "（契约写法：${contractOperation.queryParams.first { it.equals(parameter, ignoreCase = true) }}）",
                        )
                    else -> violations += Violation(
                        operation.label,
                        RULE_QUERY,
                        "契约外 query 参数 $parameter（契约声明：${contractOperation.queryParams.sorted()}）",
                    )
                }
            }

            // path 参数：声明与契约必须一一对应（多或少都不能正确路由）。
            val declaredPathParams = operation.pathParams.sorted()
            val contractPathParams = contractOperation.pathParams.sorted()
            if (declaredPathParams != contractPathParams) {
                violations += Violation(
                    operation.label,
                    RULE_PATH_PARAM,
                    "path 参数不一致：客户端 $declaredPathParams，契约 $contractPathParams",
                )
            }

            // 请求体存在性。
            when {
                contractOperation.requestBodyRequired && !operation.hasBody -> violations += Violation(
                    operation.label,
                    RULE_BODY_PRESENCE,
                    "契约要求 requestBody（required=true）但客户端未声明 @Body",
                )
                operation.hasBody && !contractOperation.hasRequestBody -> violations += Violation(
                    operation.label,
                    RULE_BODY_PRESENCE,
                    "客户端声明了 @Body ${operation.bodyType?.simpleName} 但契约该 operation 没有 requestBody",
                )
            }

            // 请求体 schema：组件名 + 字段名 + 类型 / 枚举 / 可空性 / 必填性。
            val schemaName = contractOperation.requestSchemaName
            val bodyType = operation.bodyType
            if (schemaName != null && bodyType != null && !operation.isRawBody) {
                val expectedDto = SCHEMA_NAME_ALIASES[schemaName]
                if (bodyType.simpleName != schemaName && bodyType.simpleName != expectedDto) {
                    violations += Violation(
                        operation.label,
                        RULE_BODY_SCHEMA,
                        "请求体 schema 用错：契约要求 $schemaName，客户端发送 ${bodyType.simpleName}",
                    )
                }
                val contractFields = contract.schemaPropertyNames(schemaName)
                if (contractFields == null) {
                    violations += Violation(
                        operation.label,
                        RULE_BODY_SCHEMA,
                        "契约缺少组件 schema $schemaName（无法核对字段）",
                    )
                } else {
                    val clientFields = serialNames(bodyType)
                    val clientElements = elements(bodyType)?.associateBy { it.name }
                    if (clientFields == null || clientElements == null) {
                        violations += Violation(
                            operation.label,
                            RULE_BODY_SCHEMA,
                            "无法取得 ${bodyType.simpleName} 的 kotlinx.serialization 序列名（未标注 @Serializable？）",
                        )
                    } else {
                        val extra = (clientFields - contractFields).sorted()
                        if (extra.isNotEmpty()) {
                            violations += Violation(
                                operation.label,
                                RULE_BODY_SCHEMA,
                                "${bodyType.simpleName} 含契约外字段 $extra（契约 $schemaName 字段：${contractFields.sorted()}）",
                            )
                        }
                        checkPropertySemantics(
                            operationLabel = operation.label,
                            schemaName = schemaName,
                            bodySimpleName = bodyType.simpleName,
                            clientElements = clientElements,
                            contract = contract,
                            violations = violations,
                            notes = notes,
                        )
                    }
                }
            }
        }
        return Report(violations, notes)
    }

    /** I-1：逐属性核对 type / enum / nullable / required；契约缺信息时记 NOT-VERIFIED note。 */
    private fun checkPropertySemantics(
        operationLabel: String,
        schemaName: String,
        bodySimpleName: String,
        clientElements: Map<String, ClientElement>,
        contract: OpenApiContract,
        violations: MutableList<Violation>,
        notes: MutableList<Note>,
    ) {
        val properties = contract.schemaProperties(schemaName) ?: return
        val required = contract.schemaRequired(schemaName)
        for ((name, schema) in properties) {
            val client = clientElements[name] ?: continue // 少发可选字段不判违规
            // M-B：三类不可判定情形都必须显式记 note，不能有静默跳过的分支。
            when {
                !schema.hasInfo -> notes += Note(
                    operationLabel,
                    "NOT-VERIFIED：契约属性 $schemaName.$name 没有 type/enum/\$ref，类型维度不可判定",
                )
                schema.typeName == null -> notes += Note(
                    operationLabel,
                    "NOT-VERIFIED：契约属性 $schemaName.$name 无 type，无法核对客户端 ${client.kind}",
                )
                client.kind == ClientKind.UNKNOWN -> notes += Note(
                    operationLabel,
                    "NOT-VERIFIED：客户端 $bodySimpleName.$name 的序列化 kind 不可判定，无法核对契约 type=${schema.typeName}",
                )
                schema.typeName != client.wireType -> violations += Violation(
                    operationLabel,
                    RULE_BODY_TYPE,
                    "$bodySimpleName.$name 类型与契约不符：契约 $schemaName.$name=${schema.typeName}" +
                        "${schema.format?.let { "($it)" } ?: ""}，客户端 ${client.kind}",
                )
            }

            // 枚举：契约给了 enum 就要求客户端能被枚举约束住，且客户端枚举值必须 ⊆ 契约枚举。
            //
            // M-D 口径（显式写明，不留模糊）：本仓采用**已授权的 DTO 级 enum 方案** ——
            // 请求 DTO 字段本身就是 kotlinx `@Serializable enum class`，wire 值用 `@SerialName` 对齐契约。
            // 本轮不为「在调用边界手写枚举校验」另开接缝；若将来改走边界校验，必须同时给出可判定的接缝
            // 并重新过独立 review，不能靠弱化本条判据通过。
            if (schema.enumValues != null) {
                when {
                    client.kind != ClientKind.ENUM -> violations += Violation(
                        operationLabel,
                        RULE_BODY_ENUM,
                        "$bodySimpleName.$name 无法表达契约枚举：契约 $schemaName.$name ∈ " +
                            "${schema.enumValues.sorted()}，客户端是 ${client.kind}。" +
                            "要求改为与契约一致的 DTO 级 @Serializable enum class（含 @SerialName wire 值），" +
                            "任何非法值都不会被门禁拦住。",
                    )
                    client.enumValues == null -> notes += Note(
                        operationLabel,
                        "NOT-VERIFIED：无法读取 $bodySimpleName.$name 的枚举值集合",
                    )
                    else -> {
                        val illegal = (client.enumValues - schema.enumValues).sorted()
                        if (illegal.isNotEmpty()) {
                            violations += Violation(
                                operationLabel,
                                RULE_BODY_ENUM,
                                "$bodySimpleName.$name 含契约外枚举值 $illegal（契约 ${schema.enumValues.sorted()}）",
                            )
                        }
                    }
                }
            }

            // 可空性：契约不可空而客户端可空 → 客户端可能发出契约不允许的 null。
            if (!schema.nullable && client.nullable) {
                violations += Violation(
                    operationLabel,
                    RULE_BODY_NULLABLE,
                    "$bodySimpleName.$name 可空，但契约 $schemaName.$name 不可空" +
                        "（envelope 默认 explicitNulls=true，会真的发出 null）",
                )
            }

            // 必填：契约 required 的字段在客户端不能是可选/可空。
            if (name in required && (client.optional || client.nullable)) {
                violations += Violation(
                    operationLabel,
                    RULE_BODY_REQUIRED,
                    "$bodySimpleName.$name 在契约 $schemaName 里是 required，" +
                        "但客户端${if (client.optional) "有默认值（可省略）" else ""}" +
                        "${if (client.optional && client.nullable) "且" else ""}" +
                        "${if (client.nullable) "可空" else ""}",
                )
            }
        }
    }
}
