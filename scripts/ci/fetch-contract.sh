#!/usr/bin/env bash
# 从 pim-api 拉取接口契约快照到本仓 contract/openapi.json。
#
# 注意：本脚本只更新快照文件，**不生成任何代码** —— 本仓的客户端模型是手写的，
# 需要人工对照快照核对（见 AGENTS.md「接口契约与门禁」）。
#
# 用法：
#   scripts/ci/fetch-contract.sh            # 用默认分支（master）
#   PIM_CONTRACT_REF=<sha|branch> scripts/ci/fetch-contract.sh
set -euo pipefail

REPO="${PIM_CONTRACT_REPO:-2746267826/pim-api}"
REF="${PIM_CONTRACT_REF:-master}"
RAW_BASE="${PIM_CONTRACT_RAW_BASE:-https://raw.githubusercontent.com}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/contract/openapi.json"

mkdir -p "$(dirname "$OUT")"

echo "==> 拉取契约 $REPO@$REF"
if ! curl -fsSL "$RAW_BASE/$REPO/$REF/contract/openapi.json" -o "$OUT.tmp"; then
  echo "::error::无法拉取契约 $RAW_BASE/$REPO/$REF/contract/openapi.json" >&2
  rm -f "$OUT.tmp"
  exit 1
fi

# 基本健全性检查：必须是个能解析的 JSON 对象，别把 404 页面当契约存下来
if ! head -c 1 "$OUT.tmp" | grep -q '{'; then
  echo "::error::拉到的内容不是 JSON 对象，疑似错误页" >&2
  rm -f "$OUT.tmp"
  exit 1
fi

mv "$OUT.tmp" "$OUT"
echo "已更新：$OUT ($(wc -c < "$OUT") 字节)"
echo "来源：$REPO@$REF"
