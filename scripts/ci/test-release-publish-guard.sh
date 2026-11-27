#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 行为测试：CI 发布守卫（release-publish-guard.sh）。
#
# 覆盖：首次发布、正式版不改写、过期重跑不补发、非 master 拒绝、
# tag 机器序号补偿、targetCommitish 绑定、API 错误 fail-closed。
# 全部用 stub gh，离线，不碰真实 release。
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/ci/release-publish-guard.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-android-publish-guard.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok() { echo "  ✅ $1"; pass=$((pass + 1)); }
bad() { echo "  ❌ $1"; fail=$((fail + 1)); }

mkdir -p "$TMP/bin"
cat > "$TMP/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

not_found() {
  printf '{"message":"Not Found","status":"404"}\n'
  echo "gh: Not Found (HTTP 404)" >&2
  exit 1
}

cmd="${1:-}"; shift || true
case "$cmd" in
  api)
    path="${1:-}"
    case "$path" in
      */releases/tags/*)
        case "${FAKE_RELEASE_MODE:-missing}" in
          missing) not_found ;;
          error) echo "gh: Server Error (HTTP 500)" >&2; exit 1 ;;
          json) cat "${FAKE_RELEASE_JSON:?FAKE_RELEASE_JSON required}" ;;
          *) echo "stub gh: unknown FAKE_RELEASE_MODE" >&2; exit 2 ;;
        esac
        ;;
      */commits/master)
        if [[ "${FAKE_MASTER_MODE:-ok}" == "error" ]]; then
          echo "gh: Server Error (HTTP 500)" >&2
          exit 1
        fi
        printf '{"sha":"%s"}\n' "${FAKE_MASTER_SHA:?FAKE_MASTER_SHA required}"
        ;;
      *) echo "stub gh: unsupported api $path" >&2; exit 2 ;;
    esac
    ;;
  *) echo "stub gh: unsupported command $cmd" >&2; exit 2 ;;
esac
STUB
chmod +x "$TMP/bin/gh"
export PATH="$TMP/bin:$PATH"

SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
OTHER="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

write_release() { # <file> <tag> <target> <prerelease> [draft] [tag-name-override]
  jq -n \
    --arg tag "$2" \
    --arg target "$3" \
    --argjson pre "$4" \
    --argjson draft "${5:-false}" \
    --arg tag_name "${6:-$2}" \
    '{id:500,tag_name:$tag_name,draft:$draft,prerelease:$pre,target_commitish:$target}' \
    > "$1"
}

run_case() { # <label> <publish> <reason> <exists> <env...>
  local label="$1" want_publish="$2" want_reason="$3" want_exists="$4"; shift 4
  local out rc actual attempt="${GUARD_ATTEMPT:-1}" event="${GUARD_EVENT:-push}"
  rc=0
  out="$(env "$@" bash "$SCRIPT" \
    --repo 2746267826/pim-android --tag android-v26.11.1042 \
    --sha "$SHA" --run-number 42 --run-attempt "$attempt" \
    --ref refs/heads/master --event "$event" 2>"$TMP/err")" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    bad "$label：期望成功，rc=$rc：$(cat "$TMP/err")"
    return
  fi
  actual="$(grep '^publish=' <<<"$out")"
  [[ "$actual" == "publish=$want_publish" ]] \
    || { bad "$label：publish 期望 $want_publish，实际 [$actual]"; return; }
  actual="$(grep '^reason=' <<<"$out")"
  [[ "$actual" == "reason=$want_reason" ]] \
    || { bad "$label：reason 期望 $want_reason，实际 [$actual]"; return; }
  actual="$(grep '^exists=' <<<"$out")"
  [[ "$actual" == "exists=$want_exists" ]] \
    || { bad "$label：exists 期望 $want_exists，实际 [$actual]"; return; }
  ok "$label"
}

expect_fail() { # <label> <pattern> <env...>
  local label="$1" pattern="$2"; shift 2
  local out rc
  rc=0
  out="$(env "$@" bash "$SCRIPT" \
    --repo 2746267826/pim-android --tag android-v26.11.1042 \
    --sha "$SHA" --run-number 42 --run-attempt 1 \
    --ref refs/heads/master --event push 2>&1)" || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    bad "$label：期望失败，实际通过 [$out]"
  elif ! grep -q "$pattern" <<<"$out"; then
    bad "$label：期望错误包含 [$pattern]，实际 [$out]"
  else
    ok "$label"
  fi
}

echo "== release-publish-guard.sh =="

run_case "首次发布（release 不存在）→ 允许创建" true missing false \
  FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$SHA"

GUARD_ATTEMPT=2 run_case "重跑到当前 master HEAD → 允许补发" true rerun-current-head false \
  FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$SHA"

GUARD_EVENT=workflow_dispatch run_case "非 push 手动 dispatch 且 commit=当前 HEAD → 允许" true current-head-dispatch false \
  FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$SHA"

write_release "$TMP/pre.json" android-v26.11.1042 "$SHA" true false
run_case "已存在 pre-release，首次尝试 → 允许刷新" true prerelease-refresh true \
  FAKE_RELEASE_MODE=json FAKE_RELEASE_JSON="$TMP/pre.json" FAKE_MASTER_SHA="$SHA"

write_release "$TMP/formal.json" android-v26.11.1042 "$SHA" false false
run_case "已存在正式版 → 绝不改写" false formal-exists true \
  FAKE_RELEASE_MODE=json FAKE_RELEASE_JSON="$TMP/formal.json" FAKE_MASTER_SHA="$SHA"

write_release "$TMP/other-target.json" android-v26.11.1042 "$OTHER" true false
expect_fail "release 属于别的 commit → 拒绝改写" "targetCommitish" \
  FAKE_RELEASE_MODE=json FAKE_RELEASE_JSON="$TMP/other-target.json" FAKE_MASTER_SHA="$SHA"

write_release "$TMP/branch-target.json" android-v26.11.1042 master true false
expect_fail "release targetCommitish 是分支名 → 拒绝" "targetCommitish" \
  FAKE_RELEASE_MODE=json FAKE_RELEASE_JSON="$TMP/branch-target.json" FAKE_MASTER_SHA="$SHA"

write_release "$TMP/wrong-tag.json" android-v26.11.1042 "$SHA" true false android-v26.11.1043
expect_fail "release tag_name 不一致 → 拒绝" "tag_name" \
  FAKE_RELEASE_MODE=json FAKE_RELEASE_JSON="$TMP/wrong-tag.json" FAKE_MASTER_SHA="$SHA"

write_release "$TMP/draft.json" android-v26.11.1042 "$SHA" true true
expect_fail "release 仍是 draft → 拒绝改写" "draft" \
  FAKE_RELEASE_MODE=json FAKE_RELEASE_JSON="$TMP/draft.json" FAKE_MASTER_SHA="$SHA"

# tag 机器序号必须按 +1000 补偿换算；run 41 对不上 android-v26.11.1042。
rc=0
out="$(env FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$SHA" bash "$SCRIPT" \
  --repo 2746267826/pim-android --tag android-v26.11.1042 \
  --sha "$SHA" --run-number 41 --run-attempt 1 \
  --ref refs/heads/master --event push 2>&1)" || rc=$?
if [[ "$rc" -ne 0 && "$out" == *"run number"* ]]; then ok "tag 机器序号与 run 41 对不上 → 拒绝"; else bad "tag 机器序号与 run 41 对不上 → 拒绝：rc=$rc [$out]"; fi

rc=0
out="$(env FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$SHA" bash "$SCRIPT" \
  --repo 2746267826/pim-android --tag android-v26.11.1042 \
  --sha "$SHA" --run-number 42 --run-attempt 1 \
  --ref refs/heads/feature/foo --event push 2>&1)" || rc=$?
if [[ "$rc" -ne 0 && "$out" == *"master"* ]]; then ok "非 master ref → 拒绝"; else bad "非 master ref → 拒绝：rc=$rc [$out]"; fi

rc=0
out="$(env FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$OTHER" bash "$SCRIPT" \
  --repo 2746267826/pim-android --tag android-v26.11.1042 \
  --sha "$SHA" --run-number 42 --run-attempt 2 \
  --ref refs/heads/master --event push 2>&1)" || rc=$?
if [[ "$rc" -eq 0 && "$out" == *"publish=false"* && "$out" == *"stale-rerun"* ]]; then
  ok "旧 run 重跑（commit 不是当前 master HEAD）→ publish=false"
else
  bad "旧 run 重跑 → publish=false：rc=$rc [$out]"
fi

rc=0
out="$(env FAKE_RELEASE_MODE=missing FAKE_MASTER_SHA="$OTHER" bash "$SCRIPT" \
  --repo 2746267826/pim-android --tag android-v26.11.1042 \
  --sha "$SHA" --run-number 42 --run-attempt 1 \
  --ref refs/heads/master --event workflow_dispatch 2>&1)" || rc=$?
if [[ "$rc" -eq 0 && "$out" == *"publish=false"* && "$out" == *"stale-dispatch"* ]]; then
  ok "手动 dispatch 但不是当前 HEAD → publish=false"
else
  bad "手动 dispatch 但不是当前 HEAD → publish=false：rc=$rc [$out]"
fi

rc=0
out="$(env FAKE_RELEASE_MODE=error FAKE_MASTER_SHA="$SHA" bash "$SCRIPT" \
  --repo 2746267826/pim-android --tag android-v26.11.1042 \
  --sha "$SHA" --run-number 42 --run-attempt 1 \
  --ref refs/heads/master --event push 2>&1)" || rc=$?
if [[ "$rc" -ne 0 && "$out" == *"fail-closed"* ]]; then ok "release API 500 → fail-closed"; else bad "release API 500 → fail-closed：rc=$rc [$out]"; fi

if [[ "$fail" -ne 0 ]]; then
  echo "release-publish-guard tests failed: ${fail} failed, ${pass} passed" >&2
  exit 1
fi
echo "release-publish-guard tests passed (${pass} checks)"
