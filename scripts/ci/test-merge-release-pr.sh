#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 行为测试：release bump PR 的合并判定（merge-release-pr.sh）。
#
# 重点覆盖「auto-merge=false 不得误报成功」：
#   * auto-merge 命令失败且 PR 仍 OPEN、autoMergeRequest 为空 → 立即失败；
#   * 命令返回 0 但 autoMergeRequest 一直为空 → 失败；
#   * 只有 GitHub 回报 MERGED 且 merge commit 的 VERSION 正确才算成功；
#   * 已合并对象来自别的 head / 别的 base / 错误 VERSION → 拒绝。
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/ci/merge-release-pr.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-android-merge-release.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok() { echo "  ✅ $1"; pass=$((pass + 1)); }
bad() { echo "  ❌ $1"; fail=$((fail + 1)); }

mkdir -p "$TMP/bin"
cat > "$TMP/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

cmd="${1:-}"; shift || true
case "$cmd" in
  pr)
    sub="${1:-}"; shift || true
    case "$sub" in
      view)
        # 每次读取都计数；FAKE_MERGE_DELAY_VIEWS 用来模拟 auto-merge 排队后
        # 在下一次轮询时变成 MERGED。
        views=0
        [[ -f "$TMP/views" ]] && views="$(cat "$TMP/views")"
        views=$((views + 1))
        echo "$views" > "$TMP/views"
        if [[ -n "${FAKE_MERGE_DELAY_VIEWS:-}" ]] && (( views >= FAKE_MERGE_DELAY_VIEWS )); then
          jq '.state = "MERGED" | .mergedAt = "2026-11-26T00:00:00Z" |
              .mergeCommit = {oid: $sha} | .autoMergeRequest = null' \
            --arg sha "${FAKE_MERGE_SHA:-${FAKE_SHA:?}}" "$TMP/pr.json" > "$TMP/pr.tmp"
          mv "$TMP/pr.tmp" "$TMP/pr.json"
        fi
        cat "$TMP/pr.json"
        ;;
      merge)
        if [[ "${FAKE_MERGE_MODE:-success}" == "fail" ]]; then
          echo "GraphQL: auto-merge is not enabled for this repository" >&2
          exit 1
        fi
        if [[ "${FAKE_MERGE_MODE:-success}" == "noauto" ]]; then
          exit 0
        fi
        if [[ -n "${FAKE_MERGE_DELAY_VIEWS:-}" ]]; then
          jq '.autoMergeRequest = {enabledBy: {login: "tester"}}' "$TMP/pr.json" > "$TMP/pr.tmp"
          mv "$TMP/pr.tmp" "$TMP/pr.json"
        else
          jq '.state = "MERGED" | .mergedAt = "2026-11-26T00:00:00Z" |
              .mergeCommit = {oid: $sha} | .autoMergeRequest = null' \
            --arg sha "${FAKE_MERGE_SHA:-${FAKE_SHA:?}}" "$TMP/pr.json" > "$TMP/pr.tmp"
          mv "$TMP/pr.tmp" "$TMP/pr.json"
        fi
        ;;
      *) echo "stub gh: unsupported pr $sub" >&2; exit 2 ;;
    esac
    ;;
  api)
    path="${1:-}"
    case "$path" in
      */commits/*)
        [[ "${FAKE_COMMIT_EXISTS:-true}" == "true" ]] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
        printf '{"sha":"%s"}\n' "${FAKE_MERGE_SHA:-${FAKE_SHA:?}}"
        ;;
      */contents/VERSION*)
        printf '%s' "${FAKE_CONTENT_VERSION:-1.1.0}" | base64 | tr -d '\n'
        printf '\n'
        ;;
      *) echo "stub gh: unsupported api $path" >&2; exit 2 ;;
    esac
    ;;
  *) echo "stub gh: unsupported command $cmd" >&2; exit 2 ;;
esac
STUB
chmod +x "$TMP/bin/gh"
export PATH="$TMP/bin:$PATH"
export TMP

SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
OTHER="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

write_pr() { # <state> <base> <head> <auto-json-or-null> [merge-sha]
  jq -n \
    --arg state "$1" \
    --arg base "$2" \
    --arg head "$3" \
    --argjson auto "$4" \
    --arg merge_sha "${5:-$SHA}" \
    '{state:$state,mergedAt:(if $state=="MERGED" then "2026-11-26T00:00:00Z" else null end),
      mergeCommit:{oid:(if $state=="MERGED" then $merge_sha else null end)},
      baseRefName:$base,headRefOid:$head,autoMergeRequest:$auto}' \
    > "$TMP/pr.json"
  echo 0 > "$TMP/views"
}

run_ok() { # <label> <env...>
  local label="$1"; shift
  local out rc
  rc=0
  out="$(env TMP="$TMP" FAKE_SHA="$SHA" PIM_MERGE_TIMEOUT_SECONDS=0 \
    PIM_MERGE_POLL_SECONDS=0 PIM_MERGE_AUTO_CONFIRM_SECONDS=0 \
    "$@" bash "$SCRIPT" --repo 2746267826/pim-android --pr 123 \
    --sha "$SHA" --version 1.1.0 2>"$TMP/err")" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    bad "$label：期望成功，rc=$rc：$(cat "$TMP/err")"
  elif ! grep -q "^merge_sha=${SHA}$" <<<"$out"; then
    bad "$label：缺少 merge_sha 输出 [$out]"
  else
    ok "$label"
  fi
}

run_fail() { # <label> <pattern> <env...>
  local label="$1" pattern="$2"; shift 2
  local out rc
  rc=0
  out="$(env TMP="$TMP" FAKE_SHA="$SHA" PIM_MERGE_TIMEOUT_SECONDS=0 \
    PIM_MERGE_POLL_SECONDS=0 PIM_MERGE_AUTO_CONFIRM_SECONDS=0 \
    "$@" bash "$SCRIPT" --repo 2746267826/pim-android --pr 123 \
    --sha "$SHA" --version 1.1.0 2>&1)" || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    bad "$label：期望失败，实际通过 [$out]"
  elif ! grep -q "$pattern" <<<"$out"; then
    bad "$label：期望错误包含 [$pattern]，实际 [$out]"
  else
    ok "$label"
  fi
}

echo "== merge-release-pr.sh =="

write_pr MERGED master "$SHA" null
run_ok "已合并且 head/base/VERSION 正确 → 成功" FAKE_MERGE_MODE=fail

write_pr OPEN master "$SHA" null
run_ok "auto-merge 命令成功，GitHub 回报 MERGED → 成功" FAKE_MERGE_MODE=success

write_pr OPEN master "$SHA" null
run_ok "auto-merge 排队后下一次轮询 MERGED → 成功" \
  FAKE_MERGE_MODE=success FAKE_MERGE_DELAY_VIEWS=2

write_pr OPEN master "$SHA" null
run_fail "auto-merge 未启用（命令失败、autoMergeRequest 为空）→ 立即失败" "auto-merge" \
  FAKE_MERGE_MODE=fail

write_pr OPEN master "$SHA" null
run_fail "命令返回 0 但 autoMergeRequest 一直为空 → 失败" "auto-merge" \
  FAKE_MERGE_MODE=noauto

write_pr CLOSED master "$SHA" null
run_fail "PR 被关闭 → 失败" "关闭" FAKE_MERGE_MODE=fail

write_pr OPEN develop "$SHA" null
run_fail "base 不是 master → 失败" "master" FAKE_MERGE_MODE=fail

write_pr MERGED master "$OTHER" null
run_fail "已合并 head 不是本次提交 → 拒绝沿用旧合并" "head" FAKE_MERGE_MODE=fail

write_pr MERGED master "$SHA" null "$OTHER"
run_fail "merge commit VERSION 不是目标 → 失败" "VERSION" \
  FAKE_MERGE_MODE=fail FAKE_CONTENT_VERSION=2.0.0

write_pr MERGED master "$SHA" null
run_fail "merge commit API 读取失败 → fail-closed" "合并提交" \
  FAKE_MERGE_MODE=fail FAKE_COMMIT_EXISTS=false

write_pr OPEN master "$SHA" '{"enabledBy":{"login":"tester"}}'
run_fail "auto-merge 已排队但始终不合并 → 超时失败，不误报成功" "超时" \
  FAKE_MERGE_MODE=fail

if [[ "$fail" -ne 0 ]]; then
  echo "merge-release-pr tests failed: ${fail} failed, ${pass} passed" >&2
  exit 1
fi
echo "merge-release-pr tests passed (${pass} checks)"
