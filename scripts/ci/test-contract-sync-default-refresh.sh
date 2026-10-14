#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 自检：**默认刷新链路**真的会跑完整（快照 + provenance + 重新生成 Kotlin DTO）。
#
# 为什么单独一个测试：`test-contract-sync-pr.sh` 全程用 `--refresh-cmd` 替换刷新命令，
# 它证明的是 Git/PR 编排，**证明不了默认命令接线正确** —— 独立 review 实测过：
# 把默认命令里的 `&& bash scripts/ci/generate-contract-kotlin.sh` 删掉，
# 那 30 项测试依然全绿。而"生成物没跟着重新生成"正是本仓一致性门禁会判红的东西。
#
# 做法：用一个**本地上游**（bare 仓 + file:// raw 目录）跑真实的
# `fetch-contract.sh && generate-contract-kotlin.sh`，并在 fixture 里预先放一份
# **过期的 ContractModels.kt**。只有默认命令两步都跑了，那份过期产物才会被重新生成、
# 才会出现差异、才会开 PR —— 少跑一步这个测试就会红。
#
# 安全：不设置任何"本地旁路"变量（PIM_CONTRACT_TEST_* / SNAPSHOT 之类），
# 只覆盖允许覆盖的**来源**（REPO / REF / RAW_BASE / REMOTE），所以在 CI 下同样合法。
#
# 用法：scripts/ci/test-contract-sync-default-refresh.sh
# ---------------------------------------------------------------------------
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SCRIPT="$HERE/contract-sync-pr.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-default-refresh-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
check() { if [ "$2" = "$3" ]; then echo "  ✅ $1"; pass=$((pass+1)); else echo "  ❌ $1 — 期望 [$2]，实际 [$3]"; fail=$((fail+1)); fi; }
contains() { if [[ "$3" == *"$2"* ]]; then echo "  ✅ $1"; pass=$((pass+1)); else echo "  ❌ $1 — 未见 [$2]"; fail=$((fail+1)); fi; }

GEN_PATH="src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt"
REPO_SLUG="2746267826/pim-android"
# origin 的路径刻意是 <tmp>/github.com/<owner>/<repo>.git：脚本会校验远端目标身份，
# 而任何 URL 重写（insteadOf / pushInsteadOf）都会被它拒绝，所以 fixture 不能用重写。
ORIGIN_DIR="$TMP/github.com/${REPO_SLUG}.git"
REF="4054a1b51af0f2b3e19fd5ab8f4e2f42d4a4bbdd"   # 任意 40 位 SHA；ref-pinned 时不再联网解析

# --- 本地上游：bare 仓（供 ref 解析）+ file:// raw 目录（供取字节）----------
mkdir -p "$TMP/up/contract"
cp "$ROOT/contract/openapi.json" "$TMP/up/contract/openapi.json"
cp "$ROOT/contract/provenance.json" "$TMP/up/contract/provenance.json"
git init -q "$TMP/up" 2>/dev/null || true
U="git -C $TMP/up"
$U config user.email up@pim.local; $U config user.name Up
$U add -A && $U commit -qm "upstream snapshot"
UP_SHA="$($U rev-parse HEAD)"
mkdir -p "$TMP/raw/2746267826/pim-api/$UP_SHA/contract"
cp "$TMP/up/contract/openapi.json" "$TMP/raw/2746267826/pim-api/$UP_SHA/contract/openapi.json"
EXPECT_SHA256="$(sha256sum "$TMP/up/contract/openapi.json" | cut -d' ' -f1)"
git init -q --bare "$TMP/upstream.git"
$U remote add origin "$TMP/upstream.git" 2>/dev/null || true
$U push -q origin HEAD:refs/heads/master

# --- fixture 仓：真实 scripts/ + 真实契约 + 一份**过期**的生成物 -------------
mkdir -p "$TMP/work"
WORK="$TMP/work"
cp -r "$ROOT/scripts" "$WORK/scripts"
mkdir -p "$WORK/contract" "$(dirname "$WORK/$GEN_PATH")"
cp "$ROOT/contract/openapi.json" "$WORK/contract/openapi.json"
cp "$ROOT/contract/provenance.json" "$WORK/contract/provenance.json"
# 过期生成物：只有默认命令里的 generate 步骤跑了，它才会被纠正
printf '// STALE-GENERATED-CONTENT\n' > "$WORK/$GEN_PATH"

G="git -C $WORK"
$G init -q 2>/dev/null || true
$G config user.email "dev@pim.local"; $G config user.name "PIM Dev"; $G config commit.gpgsign false
mkdir -p "$(dirname "$ORIGIN_DIR")"
git init -q --bare "$ORIGIN_DIR"
$G add -A && $G commit -qm "init"
$G branch -M master
$G remote add origin "$ORIGIN_DIR"
$G push -q -u origin master

mkdir -p "$TMP/bin"
cat > "$TMP/bin/gh" <<'STUB'
#!/usr/bin/env bash
case "${1:-} ${2:-}" in
  "pr list") exit 0 ;;
  "pr create") echo "https://example.invalid/pr/1"; exit 0 ;;
  "pr edit") exit 0 ;;
esac
exit 0
STUB
chmod +x "$TMP/bin/gh"

BRANCH="contract-sync-linux/refresh-contract"
echo "== 默认刷新链路（真实 fetch-contract.sh + generate-contract-kotlin.sh）=="

set +e
OUT="$(cd "$WORK" && \
  PATH="$TMP/bin:$PATH" \
  GH_TOKEN="stub-token" \
  GITHUB_REPOSITORY="$REPO_SLUG" \
  PIM_CONTRACT_RAW_BASE="file://$TMP/raw" \
  PIM_CONTRACT_REMOTE="$TMP/upstream.git" \
  GITHUB_OUTPUT="$TMP/out.txt" \
  bash "$SCRIPT" --contract-ref "$UP_SHA" --payload-sha256 "$EXPECT_SHA256" 2>&1)"
RC=$?
set -e

check "默认链路 → 退出码 0" "0" "$RC"
check "默认链路 → result=pr-opened（过期生成物被重新生成 → 有差异）" "pr-opened" "$(sed -n 's/^result=//p' "$TMP/out.txt" 2>/dev/null)"
contains "默认链路 → 真的跑了 generate-contract-kotlin.sh" "generate-contract-kotlin.sh" "$OUT"
contains "默认链路 → 快照 sha256 与通知一致" "$EXPECT_SHA256" "$OUT"

PUSHED_GEN="$(git -C "$ORIGIN_DIR" show "refs/heads/$BRANCH:$GEN_PATH" 2>/dev/null || echo none)"
check "默认链路 → 推送的生成物不再是过期内容" "clean" \
  "$([ "${PUSHED_GEN}" != "none" ] && [[ "$PUSHED_GEN" != *"STALE-GENERATED-CONTENT"* ]] && echo clean || echo stale)"
check "默认链路 → 推送的生成物与仓内现有生成物一致（生成器真的是权威）" "same" \
  "$([ "$PUSHED_GEN" = "$(cat "$ROOT/$GEN_PATH")" ] && echo same || echo different)"

echo ""
echo "contract-sync-default-refresh: passed=$pass failed=$fail"
[ "$fail" -eq 0 ] || exit 1
