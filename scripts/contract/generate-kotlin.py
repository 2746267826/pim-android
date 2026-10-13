#!/usr/bin/env python3
"""从契约快照生成 Kotlin DTO（kotlinx.serialization）。

用法：
    python3 scripts/contract/generate-kotlin.py --out-dir <目录> [--mapping]

设计目标（工单「接口边界契约化」）：
  * 只生成 `scripts/contract/client-operations.json` 里声明的 operation 的 schema 闭包 ——
    没声明进清单的 schema 不会进仓，声明了契约里不存在的 operation 会**直接报错**。
  * 生成规则全部机械化、无手工例外，因此「手写模型对不上契约」这种偏差在生成阶段就暴露，
    而不是等到运行时。
  * 结果只依赖 `contract/openapi.json` 的字节（输出无时间戳、无随机顺序、无环境依赖），
    可以在 CI 里离线重跑并与仓内产物逐字节比对。

字段可空性规则（与 OpenAPI 3.0 语义一致，不做任何「为了编过」的放宽）：
  * `required` 且不属于「请求方向的 readOnly」→ 非空、无默认值（调用方必须显式给值，
    序列化时一定上线；不允许用默认值把必填字段悄悄吞掉）。
  * 其余属性 → `T? = null`（契约允许缺席；`encodeDefaults=false` 时不会上线）。
  * OpenAPI 3.0：`readOnly` 属性的 `required` 只对响应生效，`writeOnly` 只对请求生效。
    生成器按 schema 在客户端实际出现的方向判定，只放宽对应方向。

类型映射：
  string（含 date-time / uuid / date / email / byte / binary）→ String
  integer int32 / 无 format → Int；integer int64 → Long
  number float → Float；number double / 无 format → Double
  boolean → Boolean；array → List<T>；object+additionalProperties → Map<String, T>
  string enum → Kotlin enum（用 @SerialName 固定 wire 值）
  integer enum → typealias 到 Int（契约没有给成员名，臆造名字等于编造接口）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = REPO_ROOT / "contract" / "openapi.json"
DEFAULT_OPERATIONS = REPO_ROOT / "scripts" / "contract" / "client-operations.json"
GENERATED_RELATIVE_PATH = Path("src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt")
PACKAGE = "com.pim.core.contract"

HTTP_METHODS = ("get", "post", "put", "delete", "patch", "head", "options", "trace")


class GenerationError(Exception):
    """生成前置条件不满足（契约/清单不一致等）——必须报错，不能产出半张契约。"""


# --------------------------------------------------------------------------- #
# 契约读取
# --------------------------------------------------------------------------- #
def load_contract(path: Path) -> dict:
    if not path.is_file():
        raise GenerationError(f"契约快照不存在：{path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:  # pragma: no cover - 防御性分支
        raise GenerationError(f"契约快照不是合法 JSON：{path}: {exc}") from exc


def load_operations(path: Path) -> list[dict]:
    if not path.is_file():
        raise GenerationError(f"operation 清单不存在：{path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    operations = data.get("operations")
    if not isinstance(operations, list) or not operations:
        raise GenerationError(f"operation 清单为空或形状不对：{path}")
    seen: set[tuple[str, str]] = set()
    for entry in operations:
        method = str(entry.get("method", "")).lower()
        op_path = str(entry.get("path", ""))
        if method not in HTTP_METHODS or not op_path.startswith("/"):
            raise GenerationError(f"operation 清单条目形状不对：{entry}")
        key = (method.upper(), op_path)
        if key in seen:
            raise GenerationError(f"operation 清单有重复条目：{key}")
        seen.add(key)
    return operations


def operation_schema(contract: dict, method: str, op_path: str) -> dict:
    paths = contract.get("paths", {})
    item = paths.get(op_path)
    if item is None:
        raise GenerationError(
            f"契约里没有路径 {op_path}（客户端清单声明了它）——"
            "需要新接口时请让后端在 pim-api 实现并刷新契约，不要在客户端造一个。"
        )
    op = item.get(method.lower())
    if op is None:
        raise GenerationError(
            f"契约里 {op_path} 没有 {method.upper()} 方法（客户端清单声明了它）——"
            f"该路径已有的方法：{sorted(k.upper() for k in item if k in HTTP_METHODS)}"
        )
    return op


# --------------------------------------------------------------------------- #
# schema 闭包与方向
# --------------------------------------------------------------------------- #
def iter_refs(node) -> list[str]:
    found: list[str] = []

    def walk(value):
        if isinstance(value, dict):
            ref = value.get("$ref")
            if isinstance(ref, str):
                found.append(ref.rsplit("/", 1)[-1])
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(node)
    return found


def success_responses(op: dict) -> list[dict]:
    result = []
    for status, response in (op.get("responses") or {}).items():
        if str(status).startswith("2"):
            result.append(response)
    if not result:
        raise GenerationError("operation 没有任何 2xx 响应")
    return result


def collect_closure(schemas: dict, roots: set[str]) -> set[str]:
    closure: set[str] = set()
    stack = list(roots)
    while stack:
        name = stack.pop()
        if name in closure:
            continue
        schema = schemas.get(name)
        if schema is None:
            raise GenerationError(f"契约 components.schemas 里没有 {name}")
        closure.add(name)
        stack.extend(child for child in iter_refs(schema) if child not in closure)
    return closure


def schema_directions(contract: dict, operations: list[dict]) -> tuple[set[str], set[str], dict[str, dict]]:
    """返回 (请求方向闭包, 响应方向闭包, operationId → 元信息)。"""
    schemas = contract.get("components", {}).get("schemas", {})
    request_roots: set[str] = set()
    response_roots: set[str] = set()
    meta: dict[str, dict] = {}
    for entry in operations:
        method = str(entry["method"])
        op_path = str(entry["path"])
        op = operation_schema(contract, method, op_path)
        request_body = op.get("requestBody") or {}
        if request_body:
            request_roots.update(iter_refs(request_body))
        for response in success_responses(op):
            response_roots.update(iter_refs(response))
        meta[f"{method} {op_path}"] = {"operation": op, "requestBody": request_body}
    return (
        collect_closure(schemas, request_roots),
        collect_closure(schemas, response_roots),
        meta,
    )


# --------------------------------------------------------------------------- #
# Kotlin 类型映射
# --------------------------------------------------------------------------- #
def enum_entry_name(value: str) -> str:
    name = "".join(ch if ch.isalnum() else "_" for ch in value).upper()
    if not name or name[0].isdigit():
        name = f"VALUE_{name}"
    return name


def scalar_type(schema: dict) -> str:
    kind = schema.get("type")
    fmt = schema.get("format")
    if kind == "string":
        return "String"
    if kind == "integer":
        return "Long" if fmt == "int64" else "Int"
    if kind == "number":
        return "Float" if fmt == "float" else "Double"
    if kind == "boolean":
        return "Boolean"
    raise GenerationError(f"无法映射的标量类型：{json.dumps(schema, sort_keys=True)}")


def kotlin_type(schema: dict, direction: str) -> str:
    """把 schema（属性或根 schema）映射成 Kotlin 类型（不含可空标记）。"""
    if not isinstance(schema, dict):
        raise GenerationError(f"schema 形状不对：{schema!r}")
    ref = schema.get("$ref")
    if isinstance(ref, str):
        return ref.rsplit("/", 1)[-1]
    kind = schema.get("type")
    if kind == "array":
        items = schema.get("items")
        if not isinstance(items, dict):
            raise GenerationError(f"array 没有 items：{json.dumps(schema, sort_keys=True)}")
        return f"List<{kotlin_type(items, direction)}>"
    if kind == "object":
        if isinstance(schema.get("additionalProperties"), dict):
            return f"Map<String, {kotlin_type(schema['additionalProperties'], direction)}>"
        if "properties" in schema:
            raise GenerationError(
                "契约里出现内联对象属性，生成器尚未支持（需要先决定命名规则）："
                f"{json.dumps(schema, sort_keys=True)[:200]}"
            )
        raise GenerationError(f"自由 object 无法映射：{json.dumps(schema, sort_keys=True)[:200]}")
    return scalar_type(schema)


def property_is_required(schema: dict, name: str, prop: dict, directions: set[str]) -> bool:
    required = name in (schema.get("required") or [])
    if not required:
        return False
    if prop.get("readOnly") and "request" in directions:
        # OpenAPI 3.0：readOnly 属性的 required 只对响应生效。
        return False
    if prop.get("writeOnly") and "response" in directions:
        return False
    return True


def render_property(schema: dict, name: str, prop: dict, directions: set[str]) -> list[str]:
    required = property_is_required(schema, name, prop, directions)
    nullable = bool(prop.get("nullable")) or not required
    type_name = kotlin_type(prop, "property")
    suffix = "?" if nullable else ""
    default = " = null" if not required else ""
    lines: list[str] = []
    if prop.get("readOnly") and name in (schema.get("required") or []):
        lines.append("    // readOnly：契约 required 只对响应生效，请求方向不发该字段。")
    lines.append(f"    val {name}: {type_name}{suffix}{default},")
    return lines


def render_enum(name: str, schema: dict) -> list[str]:
    values = schema.get("enum") or []
    kind = schema.get("type")
    if kind == "string":
        lines = [
            "/**",
            f" * 契约枚举 `{name}`：wire 值由 `@SerialName` 固定，写错取值编译期就不通过。",
            " */",
            "@Serializable",
            f"enum class {name} {{",
        ]
        seen: dict[str, str] = {}
        for value in values:
            entry = enum_entry_name(str(value))
            if entry in seen:
                raise GenerationError(f"{name} 的枚举值归一化后重名：{seen[entry]!r} / {value!r}")
            seen[entry] = str(value)
            lines.append(f'    @SerialName("{value}")')
            lines.append(f"    {entry},")
        lines.append("}")
        return lines
    if kind == "integer":
        joined = ", ".join(str(value) for value in values)
        return [
            "/**",
            f" * 契约枚举 `{name}`（整数，取值：{joined}）。",
            " * 契约没有给出成员名，臆造名字等于编造接口，因此这里保留契约类型名但按 Int 处理。",
            " */",
            f"typealias {name} = Int",
        ]
    raise GenerationError(f"不支持的枚举类型：{name} type={kind}")


def render_class(name: str, schema: dict, directions: set[str]) -> list[str]:
    lines = ["@Serializable", f"data class {name}("]
    properties = schema.get("properties") or {}
    for prop_name in sorted(properties):
        lines.extend(render_property(schema, prop_name, properties[prop_name], directions))
    lines.append(")")
    return lines


def render_schema(name: str, schema: dict, directions: set[str]) -> list[str]:
    kind = schema.get("type")
    if "enum" in schema:
        return render_enum(name, schema)
    if kind == "object" and "properties" in schema:
        return render_class(name, schema, directions)
    if kind == "object":
        return [f"typealias {name} = {kotlin_type(schema, 'schema')}"]
    return [f"typealias {name} = {kotlin_type(schema, 'schema')}"]


def direction_label(name: str, request_closure: set[str], response_closure: set[str]) -> str:
    in_request = name in request_closure
    in_response = name in response_closure
    if in_request and in_response:
        return "request+response"
    if in_request:
        return "request"
    return "response"


def generate_models(contract: dict, operations: list[dict], contract_sha: str) -> str:
    schemas = contract.get("components", {}).get("schemas", {})
    request_closure, response_closure, _ = schema_directions(contract, operations)
    closure = request_closure | response_closure

    header = [
        "// ---------------------------------------------------------------------------",
        "// 本文件由工具生成，请勿手工修改 / GENERATED FILE — DO NOT EDIT BY HAND.",
        "//",
        f"//   契约快照 : contract/openapi.json (sha256 {contract_sha})",
        "//   生成器   : scripts/contract/generate-kotlin.py",
        "//   重新生成 : bash scripts/ci/generate-contract-kotlin.sh",
        "//   一致性   : bash scripts/ci/check-contract-kotlin.sh（CI 逐字节比对，改了就红）",
        "//",
        "// 只覆盖 scripts/contract/client-operations.json 声明的 operation 的 schema 闭包；",
        "// 需要新接口时先让后端在 pim-api 实现并刷新契约，再往清单里加，不要在客户端造类型。",
        "// ---------------------------------------------------------------------------",
        f"package {PACKAGE}",
        "",
    ]

    needs_serial_name = any("enum" in schemas[name] and schemas[name].get("type") == "string" for name in closure)
    if needs_serial_name:
        header.append("import kotlinx.serialization.SerialName")
    header.append("import kotlinx.serialization.Serializable")
    header.append("")

    body: list[str] = []
    for name in sorted(closure):
        schema = schemas[name]
        directions = set()
        if name in request_closure:
            directions.add("request")
        if name in response_closure:
            directions.add("response")
        body.append(
            f"/** 契约 `components.schemas.{name}`（{direction_label(name, request_closure, response_closure)}）。 */"
        )
        body.extend(render_schema(name, schema, directions))
        body.append("")

    return "\n".join(header + body).rstrip("\n") + "\n"


# --------------------------------------------------------------------------- #
# 清单输出（给人看的映射表）
# --------------------------------------------------------------------------- #
def ref_name(schema) -> str:
    if not isinstance(schema, dict):
        return "-"
    ref = schema.get("$ref")
    if isinstance(ref, str):
        return ref.rsplit("/", 1)[-1]
    if schema.get("type") == "array":
        return f"List<{ref_name(schema.get('items'))}>"
    if schema.get("type") == "object" and isinstance(schema.get("additionalProperties"), dict):
        return f"Map<String, {ref_name(schema['additionalProperties'])}>"
    if "type" in schema:
        return f"{schema.get('type')}({schema.get('format')})" if schema.get("format") else str(schema["type"])
    return json.dumps(schema, sort_keys=True)[:60]


def json_payload_schema(response: dict):
    content = (response or {}).get("content") or {}
    media = content.get("application/json")
    if media is None:
        return None
    return media.get("schema")


def print_mapping(contract: dict, operations: list[dict]) -> None:
    print("| ApiService | METHOD path | 契约请求体 | 契约 2xx payload |")
    print("| --- | --- | --- | --- |")
    for entry in operations:
        method = str(entry["method"]).upper()
        op_path = str(entry["path"])
        op = operation_schema(contract, method, op_path)
        request_body = op.get("requestBody") or {}
        request_name = "-"
        if request_body:
            media = (request_body.get("content") or {}).get("application/json") or {}
            request_name = ref_name(media.get("schema"))
        responses = sorted(success_responses(op), key=lambda r: 0)
        payloads = []
        non_json = set()
        for response in responses:
            schema = json_payload_schema(response)
            if schema is None:
                non_json.update((response.get("content") or {}).keys())
            else:
                payloads.append(ref_name(schema))
        payload = ", ".join(dict.fromkeys(payloads)) if payloads else "(非 JSON: " + ", ".join(sorted(non_json)) + ")"
        print(
            f"| `{entry.get('apiService', '?')}` | `{method} {op_path}` | "
            f"`{request_name}` | `{payload}` |"
        )


# --------------------------------------------------------------------------- #
def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="从契约快照生成 Kotlin DTO")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--operations", type=Path, default=DEFAULT_OPERATIONS)
    parser.add_argument("--out-dir", type=Path, help="生成目录（默认写回仓内）")
    parser.add_argument("--mapping", action="store_true", help="只打印 operation → 契约类型映射表")
    args = parser.parse_args(argv)

    try:
        contract = load_contract(args.contract)
        operations = load_operations(args.operations)
        if args.mapping:
            print_mapping(contract, operations)
            return 0
        contract_bytes = args.contract.read_bytes()
        contract_sha = hashlib.sha256(contract_bytes).hexdigest()
        content = generate_models(contract, operations, contract_sha)
    except GenerationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    out_dir = args.out_dir or REPO_ROOT
    target = Path(out_dir) / GENERATED_RELATIVE_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    print(f"生成 {target.relative_to(Path(out_dir)) if Path(out_dir) in target.parents else target}（{len(content)} 字节）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
