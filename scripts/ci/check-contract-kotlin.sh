#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 契约 Kotlin DTO「生成物一致性」校验（只读；CI 调用的就是它）。
#
# 语义：用**仓内** contract/openapi.json 重新生成一次，与仓库里已提交的生成物
#       src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt
#       **逐字节比对**。不一致 → 非 0，交由 CI Gate 拦截。
#
# 它能抓到什么：
#   * 契约快照刷新了、生成物没重新生成（「快照变了产物没变」）；
#   * 有人手工改了生成物（文件头写着「请勿手工修改」，改了这里就红）；
#   * 生成器版本与仓内产物不一致（换个生成器版本重跑结果不同也会红）。
#
# 只读保证：本脚本不写任何仓内文件；重新生成写到仓外 mktemp 目录，退出时删除。
#           「谁写」= scripts/ci/generate-contract-kotlin.sh（人工 refresh）；「谁只读」= 本脚本。
#
# 离线：只用 python3 标准库 + 仓内两个文件，不联网、不装依赖、不跑 gradle。
#
# 用法：
#   bash scripts/ci/check-contract-kotlin.sh
#
# 退出码：
#   0 生成物与契约快照一致
#   1 不一致（陈旧 / 被手工改动 / 缺失）
#   2 环境缺失（没有 python3）
#   4 契约快照或 operation 清单非法（生成器拒绝产出，fail-closed）
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

CONTRACT="$ROOT/contract/openapi.json"
OPERATIONS="$ROOT/scripts/contract/client-operations.json"
GENERATED_REL="src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt"
GENERATED="$ROOT/$GENERATED_REL"

EXIT_STALE=1
EXIT_ENV=2
EXIT_CONFIG=4

if ! command -v python3 >/dev/null 2>&1; then
  echo "需要 python3（生成器只用标准库）。" >&2
  exit $EXIT_ENV
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-contract-kotlin.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

echo "== pim-android 契约 Kotlin 生成物一致性校验（只读） =="
echo "仓          : $ROOT"
echo "契约快照    : $CONTRACT"
echo "operation 清单: $OPERATIONS"
echo "仓内生成物  : $GENERATED"
echo ""

if python3 "$ROOT/scripts/contract/generate-kotlin.py" \
  --contract "$CONTRACT" \
  --operations "$OPERATIONS" \
  --out-dir "$TMP" >"$TMP/generate.log" 2>&1; then
  cat "$TMP/generate.log"
else
  cat "$TMP/generate.log" >&2
  echo "" >&2
  echo "生成器拒绝产出：契约快照或 operation 清单与生成规则不符（不是「跳过」，是配置错误）。" >&2
  exit $EXIT_CONFIG
fi

if [ ! -f "$GENERATED" ]; then
  echo "" >&2
  echo "仓内缺少生成物：$GENERATED" >&2
  echo "请运行 bash scripts/ci/generate-contract-kotlin.sh 并提交生成物。" >&2
  exit $EXIT_STALE
fi

if cmp -s "$GENERATED" "$TMP/$GENERATED_REL"; then
  echo ""
  echo "✅ 生成物与 contract/openapi.json 一致（逐字节相同）。"
  exit 0
fi

echo "" >&2
echo "❌ 生成物与契约快照不一致：$GENERATED_REL" >&2
echo "" >&2
diff -u "$GENERATED" "$TMP/$GENERATED_REL" | head -n 80 >&2 || true
echo "" >&2
echo "修复方式：bash scripts/ci/generate-contract-kotlin.sh，然后提交生成物。" >&2
echo "不要手工编辑生成物 —— 文件头写着「本文件由工具生成，请勿手工修改」。" >&2
exit $EXIT_STALE
