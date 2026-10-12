#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 契约新鲜度设施 · 行为测试（离线；唯一外部前置是本机 python3 + PyYAML）
#
# 覆盖：
#   ① 同字节 → check 0 且仓内零写入                 ② 合法 JSON 但内容不同 → 非 0
#   ③ 取源失败（不可达 / 404）→ 非 0                ④ payload HTML / 空体 → 非 0
#   ⑤ PIM_CONTRACT_REPO/REF 真实改变被访问路径（含 40-hex 直用不解析）
#   ⑥ check 全程零写入（inode/mtime + git status）+ 静态只读守卫
#   ⑦ provenance：一致 → PASS；与快照不符 / 缺失 → FAIL；resolvedRef 滞后 → PASS
#   ⑧ refresh 的 provenance/bytes/hash/可复现性（两个产物）  ⑨ refresh 失败不写两个产物
#   ⑩ 上游非契约提交但字节相同 → PASS         ⑪ CI 环境下测试旁路被拒（非静默放行）
#   ⑫ 原样抽取真实 CI YAML step 与 gate check 函数，证明失败传播
#
# 本仓与 pim-web 的差异：本仓**没有 generated.ts / 没有 openapi-typescript 生成步骤**，
# 因此不装 npm、不假造生成物；入仓产物只有 contract/openapi.json 与 contract/provenance.json。
#
# 上游 fixture 一律是本机 git 仓 + 本机 HTTP 服务（127.0.0.1 随机端口），
# 生产旁路（本地文件当权威源）只在测试替身内可用，并被 ⑪ 证伪。
#
# 用法：bash scripts/ci/test-contract-freshness.sh
#   PIM_CONTRACT_TEST_KEEP=1   保留临时工作目录（默认删除）
#   PYTHONDONTWRITEBYTECODE=1  由脚本自身强制设置
# ---------------------------------------------------------------------------
set -uo pipefail
export PYTHONDONTWRITEBYTECODE=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/ci/contract-freshness-lib.sh
. "$HERE/contract-freshness-lib.sh"

CHECK="$HERE/check-contract-freshness.sh"
FETCH="$HERE/fetch-contract.sh"
CI_YML="$ROOT/.github/workflows/ci.yml"

PASS=0; FAIL=0
say()  { printf '%s\n' "$*"; }
sha()  { [ -f "$1" ] && sha256sum "$1" | cut -d' ' -f1 || echo ABSENT; }
bytes(){ [ -f "$1" ] && wc -c < "$1" | tr -d ' ' || echo ABSENT; }
fsig() { [ -e "$1" ] && stat -c '%i:%s:%Y' "$1" || echo ABSENT; }
record() { # record <name> <0=ok> <observed>
  if [ "$2" = "0" ]; then PASS=$((PASS+1)); say "PASS  $1 :: $3"
  else FAIL=$((FAIL+1)); say "FAIL  $1 :: $3"; fi
}
section() { say ""; say "-- $* --"; }

if ! command -v python3 >/dev/null 2>&1; then
  say "缺少 python3（本设施的必要前置）；无法运行行为测试。"
  exit 2
fi
if ! python3 -c 'import yaml' 2>/dev/null; then
  say "缺少 PyYAML（用于原样抽取真实 CI step/gate）；无法完成 ⑫。"
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pim-contract-test.XXXXXX")"
SRV_PID=""
cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  if [ "${PIM_CONTRACT_TEST_KEEP:-0}" = "1" ]; then say "保留工作目录：$WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

# =========================== fixtures ======================================
FIX="$WORK/fixtures"
mkdir -p "$FIX"

cat > "$FIX/openapi.master.json" <<'JSON'
{
  "openapi": "3.0.1",
  "info": { "title": "Pim.Api", "version": "1.0" },
  "paths": {
    "/api/v1/mobile/health": {
      "get": {
        "responses": {
          "200": {
            "description": "OK",
            "content": {
              "application/json": { "schema": { "$ref": "#/components/schemas/MobileHealthResponse" } }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "MobileHealthResponse": {
        "type": "object",
        "properties": {
          "status": { "type": "integer", "format": "int32" },
          "message": { "type": "string" }
        }
      }
    }
  }
}
JSON

cat > "$FIX/openapi.v0.json" <<'JSON'
{
  "openapi": "3.0.1",
  "info": { "title": "Pim.Api", "version": "1.0" },
  "paths": {
    "/api/v1/mobile/health": {
      "get": {
        "responses": {
          "200": {
            "description": "OK",
            "content": {
              "application/json": { "schema": { "$ref": "#/components/schemas/MobileHealthResponse" } }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "MobileHealthResponse": {
        "type": "object",
        "properties": { "status": { "type": "integer", "format": "int32" } }
      }
    }
  }
}
JSON

printf '<html>404 Not Found</html>\n' > "$FIX/openapi.html"
: > "$FIX/openapi.empty"

# ===================== 本机上游 git 仓（真实 40-hex SHA）====================
UPSTREAM_REPO="$WORK/upstream-src"
mkdir -p "$UPSTREAM_REPO/contract"
git -C "$UPSTREAM_REPO" init -q
git -C "$UPSTREAM_REPO" symbolic-ref HEAD refs/heads/master   # 不依赖宿主 init.defaultBranch
git -C "$UPSTREAM_REPO" config user.email fixture@local
git -C "$UPSTREAM_REPO" config user.name fixture
cp "$FIX/openapi.master.json" "$UPSTREAM_REPO/contract/openapi.json"
git -C "$UPSTREAM_REPO" add -A
git -C "$UPSTREAM_REPO" commit -qm 'c1: contract fixture'
SHA_C1="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"

# master 前进一个「非契约」提交：契约字节完全不变
printf '# fixture upstream\n' > "$UPSTREAM_REPO/README.md"
git -C "$UPSTREAM_REPO" add -A
git -C "$UPSTREAM_REPO" commit -qm 'c2: non-contract change only'
SHA_MASTER="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"

git -C "$UPSTREAM_REPO" checkout -q -b v0 "$SHA_C1"
cp "$FIX/openapi.v0.json" "$UPSTREAM_REPO/contract/openapi.json"
git -C "$UPSTREAM_REPO" commit -qam 'v0: older contract'
SHA_V0="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"
git -C "$UPSTREAM_REPO" checkout -q master

git -C "$UPSTREAM_REPO" checkout -q -b html "$SHA_C1"
cp "$FIX/openapi.html" "$UPSTREAM_REPO/contract/openapi.json"
git -C "$UPSTREAM_REPO" commit -qam 'html: error page payload'
SHA_HTML="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"

git -C "$UPSTREAM_REPO" checkout -q -b empty "$SHA_C1"
cp "$FIX/openapi.empty" "$UPSTREAM_REPO/contract/openapi.json"
git -C "$UPSTREAM_REPO" commit -qam 'empty: empty payload'
SHA_EMPTY="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"
git -C "$UPSTREAM_REPO" checkout -q master

# ===================== 本机 raw 源（静态目录树）=============================
SERVE="$WORK/serve"
lay() { # lay <repo> <sha> <file>
  mkdir -p "$SERVE/$1/$2/contract"
  cp "$3" "$SERVE/$1/$2/contract/openapi.json"
}
lay pim-api "$SHA_C1"     "$FIX/openapi.master.json"
lay pim-api "$SHA_MASTER" "$FIX/openapi.master.json"
lay pim-api "$SHA_V0"     "$FIX/openapi.v0.json"
lay pim-api "$SHA_HTML"   "$FIX/openapi.html"
lay pim-api "$SHA_EMPTY"  "$FIX/openapi.empty"
lay 2746267826/pim-api "$SHA_MASTER" "$FIX/openapi.master.json"   # REPO 覆写用例

PORT="$(python3 - <<'PY'
import socket
s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()
PY
)"
( cd "$SERVE" && exec python3 -u -m http.server "$PORT" --bind 127.0.0.1 ) > "$WORK/http-server.log" 2>&1 &
SRV_PID=$!
RAW="http://127.0.0.1:$PORT"
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "$RAW/pim-api/$SHA_MASTER/contract/openapi.json" 2>/dev/null && break
  sleep 0.1
done

# =========================== sandbox =======================================
sandbox() { # sandbox <dir>
  local dir="$1"
  rm -rf "$dir"
  mkdir -p "$dir/scripts/ci" "$dir/contract" "$dir/.github/workflows"
  cp "$HERE/contract-freshness-lib.sh" "$dir/scripts/ci/"
  cp "$CHECK" "$dir/scripts/ci/"
  cp "$FETCH" "$dir/scripts/ci/"
  cp "$CI_YML" "$dir/.github/workflows/ci.yml"
  git -C "$dir" init -q
  git -C "$dir" config user.email test@local
  git -C "$dir" config user.name test
  git -C "$dir" add -A
  git -C "$dir" commit -qm sandbox-base
}

refresh_sandbox() { # refresh_sandbox <dir> <ref>  -> rc；不 commit
  local dir="$1" ref="$2"
  ( cd "$dir" && PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=pim-api \
      PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF="$ref" \
      bash scripts/ci/fetch-contract.sh ) > "$dir.refresh.log" 2>&1
}

commit_sandbox() { local dir="$1"; git -C "$dir" add -A; git -C "$dir" commit -qm "$2"; }

run_check() { # run_check <dir> <ref> [raw_base] -> 设置 CHECK_RC / CHECK_LOG
  local dir="$1" ref="$2" base="${3:-$RAW}"
  ( cd "$dir" && PIM_CONTRACT_RAW_BASE="$base" PIM_CONTRACT_REPO=pim-api \
      PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF="$ref" \
      bash scripts/ci/check-contract-freshness.sh ) > "$dir.check.log" 2>&1
  CHECK_RC=$?
  CHECK_LOG="$dir.check.log"
}
run_check_env() { # run_check_env <dir> <ref> <env...>  额外 env 前缀（用于旁路/CI 用例）
  local dir="$1" ref="$2"; shift 2
  ( cd "$dir" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=pim-api \
      PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF="$ref" "$@" \
      bash scripts/ci/check-contract-freshness.sh ) > "$dir.check.log" 2>&1
  CHECK_RC=$?
  CHECK_LOG="$dir.check.log"
}
artifacts_sig() { # 两个产物的 inode:size:mtime
  printf '%s | %s' "$(fsig "$1/contract/openapi.json")" "$(fsig "$1/contract/provenance.json")"
}
artifacts_sha() {
  printf '%s | %s' "$(sha "$1/contract/openapi.json")" "$(sha "$1/contract/provenance.json")"
}

# raw 服务的访问日志：按「调用前 mark → 调用后切片」断言真实请求路径
ACCESS_LOG="$WORK/http-server.log"
log_mark()  { wc -l < "$ACCESS_LOG" | tr -d ' '; }
log_since() { tail -n "+$(( $1 + 1 ))" "$ACCESS_LOG" 2>/dev/null; }
has_req()   { case "$(log_since "$1")" in *"$2"*) return 0;; *) return 1;; esac; }

say "== pim-android 契约新鲜度行为测试（离线 fixture 上游）=="
say "仓          : $ROOT"
say "fixture 上游: $UPSTREAM_REPO  (master=$SHA_MASTER c1=$SHA_C1 v0=$SHA_V0)"
say "fixture raw : $RAW"
say "工作目录    : $WORK"

# ======================= ① 一致 → 0，且零写入 ==============================
section "① 同字节 → check 0 且仓内零写入"
sandbox "$WORK/c1"
refresh_sandbox "$WORK/c1" master >/dev/null 2>&1; R_RC=$?
commit_sandbox "$WORK/c1" 'synced at master'
record "refresh_synced_sandbox" $([ "$R_RC" -eq 0 ] && echo 0 || echo 1) "refresh rc=$R_RC"
SIG_BEFORE="$(artifacts_sig "$WORK/c1")"
STATUS_BEFORE="$(git -C "$WORK/c1" status --porcelain)"
run_check "$WORK/c1" master
SIG_AFTER="$(artifacts_sig "$WORK/c1")"
STATUS_AFTER="$(git -C "$WORK/c1" status --porcelain)"
say "   rc=$CHECK_RC  artifacts $SIG_BEFORE -> $SIG_AFTER"
record "check_consistent_exit_zero" $([ "$CHECK_RC" -eq 0 ] && echo 0 || echo 1) "rc=$CHECK_RC on identical upstream bytes"
record "check_consistent_no_write" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "inode/size/mtime unchanged"
record "check_consistent_clean_tree" $([ "$STATUS_BEFORE" = "$STATUS_AFTER" ] && echo 0 || echo 1) "git status --porcelain unchanged ('${STATUS_AFTER}')"
record "check_consistent_clean_tree_empty" $([ -z "$STATUS_AFTER" ] && echo 0 || echo 1) "git status --porcelain is empty"

# ======================= ② 合法 JSON 但内容不同 → 非 0 =====================
section "② 合法 JSON、内容不同（陈旧快照）→ 非 0"
sandbox "$WORK/c2"
refresh_sandbox "$WORK/c2" v0 >/dev/null 2>&1
commit_sandbox "$WORK/c2" 'synced at v0'
SIG_BEFORE="$(artifacts_sig "$WORK/c2")"
run_check "$WORK/c2" master
SIG_AFTER="$(artifacts_sig "$WORK/c2")"
say "   rc=$CHECK_RC  tail: $(tail -1 "$CHECK_LOG")"
record "check_stale_snapshot_exit_nonzero" $([ "$CHECK_RC" -ne 0 ] && echo 0 || echo 1) "rc=$CHECK_RC with a legal-but-different snapshot"
record "check_stale_snapshot_exit_1" $([ "$CHECK_RC" -eq 1 ] && echo 0 || echo 1) "rc=$CHECK_RC (documented stale code = 1)"
record "check_stale_snapshot_no_write" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "inode/size/mtime unchanged"

# ======================= ③ 取源失败 → 非 0（fail-closed）===================
section "③ 取不到权威源 → 非 0（fail-closed）"
sandbox "$WORK/c3"
refresh_sandbox "$WORK/c3" master >/dev/null 2>&1
commit_sandbox "$WORK/c3" 'synced at master'
SIG_BEFORE="$(artifacts_sig "$WORK/c3")"
run_check "$WORK/c3" master "http://127.0.0.1:1"
SIG_AFTER="$(artifacts_sig "$WORK/c3")"
say "   unreachable rc=$CHECK_RC"
record "check_unreachable_exit_nonzero" $([ "$CHECK_RC" -ne 0 ] && echo 0 || echo 1) "rc=$CHECK_RC when the raw base is unreachable"
record "check_unreachable_exit_3" $([ "$CHECK_RC" -eq 3 ] && echo 0 || echo 1) "rc=$CHECK_RC (documented fetch-failure code = 3)"
record "check_unreachable_no_write" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "inode/size/mtime unchanged"

# 404：源上根本没有该 SHA 的字节
mkdir -p "$SERVE/pim-api/0000000000000000000000000000000000000000/contract"
rm -f "$SERVE/pim-api/0000000000000000000000000000000000000000/contract/openapi.json"
SIG_BEFORE="$(artifacts_sig "$WORK/c3")"
run_check "$WORK/c3" "0000000000000000000000000000000000000000"
SIG_AFTER="$(artifacts_sig "$WORK/c3")"
say "   404 rc=$CHECK_RC"
record "check_http404_exit_nonzero" $([ "$CHECK_RC" -ne 0 ] && echo 0 || echo 1) "rc=$CHECK_RC when the source returns 404"
record "check_http404_exit_3" $([ "$CHECK_RC" -eq 3 ] && echo 0 || echo 1) "rc=$CHECK_RC (documented fetch-failure code = 3)"
record "check_http404_no_write" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "inode/size/mtime unchanged"

# ======================= ④ payload HTML / 空体 → 非 0 ======================
section "④ 权威源返回 HTML / 空体 → 非 0（不得当成契约比对）"
for kind in html empty; do
  case "$kind" in html) REFCASE="$SHA_HTML"; EXPECT=4;; empty) REFCASE="$SHA_EMPTY"; EXPECT=4;; esac
  SIG_BEFORE="$(artifacts_sig "$WORK/c3")"
  run_check "$WORK/c3" "$REFCASE"
  SIG_AFTER="$(artifacts_sig "$WORK/c3")"
  say "   $kind rc=$CHECK_RC  tail: $(tail -1 "$CHECK_LOG")"
  record "check_payload_${kind}_exit_nonzero" $([ "$CHECK_RC" -ne 0 ] && echo 0 || echo 1) "rc=$CHECK_RC for a $kind payload"
  record "check_payload_${kind}_exit_${EXPECT}" $([ "$CHECK_RC" -eq "$EXPECT" ] && echo 0 || echo 1) "rc=$CHECK_RC (documented payload code = $EXPECT)"
  record "check_payload_${kind}_no_write" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "inode/size/mtime unchanged"
done

# ======================= ⑤ REPO/REF 真实改变被访问路径 =====================
section "⑤ PIM_CONTRACT_REPO / REF 真实改变被访问路径（不是「变量存在」）"
ACCESS_LOG="$WORK/http-server.log"
sandbox "$WORK/c5"
refresh_sandbox "$WORK/c5" master >/dev/null 2>&1
commit_sandbox "$WORK/c5" 'synced at master'

M="$(log_mark)"
run_check "$WORK/c5" master                       # REF=master -> SHA_MASTER
if has_req "$M" "GET /pim-api/$SHA_MASTER/contract/openapi.json"; then
  record "ref_master_accesses_resolved_sha_path" 0 "REF=master requested /pim-api/$SHA_MASTER/contract/openapi.json (rc=$CHECK_RC)"
else
  record "ref_master_accesses_resolved_sha_path" 1 "no such request in the raw-server log"
fi

M="$(log_mark)"
run_check "$WORK/c5" "$SHA_MASTER"                # REF=40-hex 直接使用
if has_req "$M" "GET /pim-api/$SHA_MASTER/contract/openapi.json"; then
  record "ref_pinned_sha_accesses_same_path" 0 "REF=<40-hex> reused verbatim in the request path (rc=$CHECK_RC)"
else
  record "ref_pinned_sha_accesses_same_path" 1 "no such request in the raw-server log"
fi

# REF 换成 40-hex 时必须不再走 ls-remote：把 REMOTE 指向不存在的路径，仍应成功
( cd "$WORK/c5" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=pim-api \
    PIM_CONTRACT_REMOTE="$WORK/does-not-exist.git" PIM_CONTRACT_REF="$SHA_MASTER" \
    bash scripts/ci/check-contract-freshness.sh ) > "$WORK/c5.check.log" 2>&1
PIN_RC=$?
record "ref_pinned_skips_ls_remote" $([ "$PIN_RC" -eq 0 ] && echo 0 || echo 1) "rc=$PIN_RC with an unusable PIM_CONTRACT_REMOTE (ls-remote not called)"

# 换 REF -> v0，请求路径随解析出的 SHA 改变（沙箱快照也换成 v0 才可能一致）
sandbox "$WORK/c5b"
refresh_sandbox "$WORK/c5b" v0 >/dev/null 2>&1
commit_sandbox "$WORK/c5b" 'synced at v0'
M="$(log_mark)"
run_check "$WORK/c5b" v0
if has_req "$M" "GET /pim-api/$SHA_V0/contract/openapi.json"; then
  record "ref_change_changes_access_path" 0 "REF=v0 requested /pim-api/$SHA_V0/contract/openapi.json (rc=$CHECK_RC)"
else
  record "ref_change_changes_access_path" 1 "no such request in the raw-server log"
fi
if has_req "$M" "GET /pim-api/$SHA_MASTER/contract/openapi.json"; then
  record "ref_change_not_using_master_path" 1 "REF=v0 still requested the master SHA path"
else
  record "ref_change_not_using_master_path" 0 "REF=v0 did not request the master SHA path"
fi

# 换 REPO -> 2746267826/pim-api，请求路径随 REPO 改变
sandbox "$WORK/c5c"
( cd "$WORK/c5c" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=2746267826/pim-api \
    PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF=master \
    bash scripts/ci/fetch-contract.sh ) > "$WORK/c5c.refresh.log" 2>&1
commit_sandbox "$WORK/c5c" 'synced with overridden repo'
M="$(log_mark)"
( cd "$WORK/c5c" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=2746267826/pim-api \
    PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF=master \
    bash scripts/ci/check-contract-freshness.sh ) > "$WORK/c5c.check.log" 2>&1
REPO_RC=$?
if has_req "$M" "GET /2746267826/pim-api/$SHA_MASTER/contract/openapi.json"; then
  record "repo_change_changes_access_path" 0 "REPO=2746267826/pim-api requested /2746267826/pim-api/$SHA_MASTER/... (rc=$REPO_RC)"
else
  record "repo_change_changes_access_path" 1 "no such request in the raw-server log (rc=$REPO_RC)"
fi

# ======================= ⑥ check 静态只读守卫 ==============================
section "⑥ check 只读（运行时零写入已由 ①②③④ 覆盖；此处补静态守卫）"
if grep -nE "(^|[^a-zA-Z_])cp[[:space:]].*(contract/openapi\.json|provenance\.json)|>[[:space:]]*[\"']?\S*(contract/openapi\.json|provenance\.json)|mv[[:space:]].*(contract/openapi\.json|provenance\.json)" "$CHECK" >/dev/null 2>&1; then
  record "check_script_has_no_repo_write_ops" 1 "found a write-looking operation in check-contract-freshness.sh"
else
  record "check_script_has_no_repo_write_ops" 0 "no cp/>/mv targeting the two artifacts in check-contract-freshness.sh"
fi
ALL_STATUS="$(git -C "$WORK/c1" status --porcelain; git -C "$WORK/c2" status --porcelain; git -C "$WORK/c3" status --porcelain; git -C "$WORK/c5" status --porcelain)"
record "no_sandbox_dirtied_by_any_check_run" $([ -z "$ALL_STATUS" ] && echo 0 || echo 1) "all sandboxes still clean after every check invocation"

# ======================= ⑦ provenance 语义 =================================
section "⑦ provenance：与快照一致 → PASS；不符 / 缺失 → FAIL；resolvedRef 滞后 → PASS"
# 一致（① 已覆盖 rc=0）；这里先证 resolvedRef 滞后仍 PASS
sandbox "$WORK/c7"
refresh_sandbox "$WORK/c7" "$SHA_C1" >/dev/null 2>&1      # provenance.resolvedRef = c1
commit_sandbox "$WORK/c7" 'synced at c1'
PROV_SHA="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["resolvedRef"])' "$WORK/c7/contract/provenance.json" 2>/dev/null || echo missing)"
run_check "$WORK/c7" master                               # 上游 HEAD 已是 c2（非契约提交），字节不变
say "   rc=$CHECK_RC  provenance.resolvedRef=$PROV_SHA  今天解析出的上游=$SHA_MASTER"
record "provenance_may_lag_head_pass" $([ "$CHECK_RC" -eq 0 ] && echo 0 || echo 1) "rc=$CHECK_RC although provenance.resolvedRef=$SHA_C1 != today's HEAD $SHA_MASTER"
record "provenance_may_lag_head_kept" $([ "$PROV_SHA" = "$SHA_C1" ] && echo 0 || echo 1) "provenance.resolvedRef stayed $SHA_C1"
record "noncontract_commit_head_really_moved" $([ "$SHA_C1" != "$SHA_MASTER" ] && echo 0 || echo 1) "c1=$SHA_C1 != c2=$SHA_MASTER"

# 把 resolvedRef 手动改成另一个合法 40-hex（并非今天 HEAD）→ 仍必须 PASS（允许滞后）
python3 - "$WORK/c7/contract/provenance.json" <<'PY'
import json, sys
p = sys.argv[1]
doc = json.load(open(p, encoding="utf-8"))
doc["resolvedRef"] = "0" * 40
with open(p, "w", encoding="utf-8") as fh:
    json.dump(doc, fh, ensure_ascii=False, indent=2); fh.write("\n")
PY
run_check "$WORK/c7" master
record "provenance_other_valid_sha_still_pass" $([ "$CHECK_RC" -eq 0 ] && echo 0 || echo 1) "rc=$CHECK_RC with resolvedRef rewritten to another valid 40-hex"

# provenance 与仓内快照不一致 → rc1，且 check 不得改写任何产物
python3 - "$WORK/c7/contract/provenance.json" <<'PY'
import json, sys
p = sys.argv[1]
doc = json.load(open(p, encoding="utf-8"))
doc["sha256"] = "f" * 64
with open(p, "w", encoding="utf-8") as fh:
    json.dump(doc, fh, ensure_ascii=False, indent=2); fh.write("\n")
PY
SIG_BEFORE="$(artifacts_sig "$WORK/c7")"
run_check "$WORK/c7" master
SIG_AFTER="$(artifacts_sig "$WORK/c7")"
say "   tampered provenance rc=$CHECK_RC  tail: $(tail -1 "$CHECK_LOG")"
record "provenance_snapshot_mismatch_exit_1" $([ "$CHECK_RC" -eq 1 ] && echo 0 || echo 1) "rc=$CHECK_RC when provenance.sha256 does not match the snapshot"
record "provenance_snapshot_mismatch_no_write" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "inode/size/mtime unchanged"

# provenance 缺失 → rc1（刷新来源不可复核不得放过）
rm -f "$WORK/c7/contract/provenance.json"
run_check "$WORK/c7" master
say "   missing provenance rc=$CHECK_RC  tail: $(tail -1 "$CHECK_LOG")"
record "provenance_missing_exit_1" $([ "$CHECK_RC" -eq 1 ] && echo 0 || echo 1) "rc=$CHECK_RC when contract/provenance.json is absent"

# ======================= ⑧ refresh 产物与可复现性 ==========================
section "⑧ refresh：provenance / bytes / hash / 可复现（两个产物）"
sandbox "$WORK/c8"
refresh_sandbox "$WORK/c8" master; R_RC=$?
record "refresh_exit_zero" $([ "$R_RC" -eq 0 ] && echo 0 || echo 1) "refresh rc=$R_RC against the fixture upstream"
PROV="$WORK/c8/contract/provenance.json"
SNAP="$WORK/c8/contract/openapi.json"
record "refresh_writes_provenance" $([ -f "$PROV" ] && echo 0 || echo 1) "contract/provenance.json present"
record "refresh_writes_snapshot_bytes" \
  $([ "$(sha "$SNAP")" = "$(sha "$FIX/openapi.master.json")" ] && echo 0 || echo 1) \
  "snapshot bytes == upstream master bytes"
if [ -f "$PROV" ]; then
  EFFECTIVE="$(python3 - "$PROV" "$SNAP" "$SHA_MASTER" <<'PY'
import json, sys, hashlib, re
prov, snap_path, expect_sha = sys.argv[1:4]
doc = json.load(open(prov, encoding="utf-8"))
snap = open(snap_path, "rb").read()
problems = []
if doc.get("schemaVersion") != 1: problems.append("schemaVersion")
if doc.get("repo") != "pim-api": problems.append("repo=%r" % doc.get("repo"))
if doc.get("ref") != "master": problems.append("ref=%r" % doc.get("ref"))
if not re.match(r"^[0-9a-f]{40}$", str(doc.get("resolvedRef"))): problems.append("resolvedRef")
if not doc.get("resolvedRefSource"): problems.append("resolvedRefSource")
if doc.get("sourcePath") != "contract/openapi.json": problems.append("sourcePath")
if doc.get("bytes") != len(snap): problems.append("bytes")
if doc.get("sha256") != hashlib.sha256(snap).hexdigest(): problems.append("sha256")
for key in doc:
    if re.search(r"(AtUtc|timestamp|Timestamp|fetchedAt|generatedAt|date)", key):
        problems.append("non-deterministic field %s" % key)
print("|".join(problems) if problems else "OK")
PY
)"
  record "refresh_provenance_shape_ok" $([ "$EFFECTIVE" = "OK" ] && echo 0 || echo 1) "provenance fields: $EFFECTIVE"
  RESOLVED="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["resolvedRef"])' "$PROV")"
  record "refresh_provenance_resolved_ref" $([ "$RESOLVED" = "$SHA_MASTER" ] && echo 0 || echo 1) "resolvedRef=$RESOLVED == fixture master=$SHA_MASTER"
else
  record "refresh_provenance_shape_ok" 1 "provenance.json missing"
  record "refresh_provenance_resolved_ref" 1 "provenance.json missing"
fi

# 可复现：再刷一次，两个产物必须逐字节相同
SIG1="$(artifacts_sha "$WORK/c8")"
refresh_sandbox "$WORK/c8" master >/dev/null 2>&1; R2_RC=$?
SIG2="$(artifacts_sha "$WORK/c8")"
record "refresh_deterministic_second_run" $([ "$SIG1" = "$SIG2" ] && echo 0 || echo 1) "second refresh rc=$R2_RC left the two artifacts byte-identical"
say "   artifacts sha: $SIG2"

# ======================= ⑨ refresh 失败不写两个产物 ========================
section "⑨ refresh 失败：两个产物一个字节都不写"
sandbox "$WORK/c9"
refresh_sandbox "$WORK/c9" master >/dev/null 2>&1  # sanity: 成功刷新不改字节
SIG_BEFORE="$(artifacts_sha "$WORK/c9")"
P_BEFORE="$(fsig "$WORK/c9/contract/provenance.json")"
( cd "$WORK/c9" && env PIM_CONTRACT_RAW_BASE="http://127.0.0.1:1" PIM_CONTRACT_REPO=pim-api \
    PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF=master \
    bash scripts/ci/fetch-contract.sh ) > "$WORK/c9.fail1.log" 2>&1
F1RC=$?
SIG_AFTER="$(artifacts_sha "$WORK/c9")"
record "refresh_fetch_failure_nonzero" $([ "$F1RC" -ne 0 ] && echo 0 || echo 1) "rc=$F1RC when the source is unreachable"
record "refresh_fetch_failure_writes_nothing" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "two artifacts unchanged"

( cd "$WORK/c9" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=pim-api \
    PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF=html \
    bash scripts/ci/fetch-contract.sh ) > "$WORK/c9.fail2.log" 2>&1
F2RC=$?
SIG_AFTER="$(artifacts_sha "$WORK/c9")"
record "refresh_bad_payload_nonzero" $([ "$F2RC" -ne 0 ] && echo 0 || echo 1) "rc=$F2RC for an HTML payload"
record "refresh_bad_payload_writes_nothing" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "two artifacts unchanged"

( cd "$WORK/c9" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=pim-api \
    PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF=empty \
    bash scripts/ci/fetch-contract.sh ) > "$WORK/c9.fail3.log" 2>&1
F3RC=$?
SIG_AFTER="$(artifacts_sha "$WORK/c9")"
record "refresh_empty_payload_nonzero" $([ "$F3RC" -ne 0 ] && echo 0 || echo 1) "rc=$F3RC for an empty payload"
record "refresh_empty_payload_writes_nothing" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "two artifacts unchanged"

# 未知位置参数必须报错且不写
( cd "$WORK/c9" && bash scripts/ci/fetch-contract.sh --check ) > "$WORK/c9.fail4.log" 2>&1
F4RC=$?
SIG_AFTER="$(artifacts_sha "$WORK/c9")"
record "refresh_unknown_arg_nonzero" $([ "$F4RC" -ne 0 ] && echo 0 || echo 1) "rc=$F4RC for the unknown arg '--check'"
record "refresh_unknown_arg_writes_nothing" $([ "$SIG_BEFORE" = "$SIG_AFTER" ] && echo 0 || echo 1) "two artifacts unchanged (and provenance mtime unchanged: $([ "$P_BEFORE" = "$(fsig "$WORK/c9/contract/provenance.json")" ] && echo yes || echo no))"

# refresh 失败时「本不存在的 provenance 不得被创建」
sandbox "$WORK/c9b"
rm -f "$WORK/c9b/contract/provenance.json" "$WORK/c9b/contract/openapi.json"
commit_sandbox "$WORK/c9b" 'no artifacts yet'
( cd "$WORK/c9b" && env PIM_CONTRACT_RAW_BASE="$RAW" PIM_CONTRACT_REPO=pim-api \
    PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF=html \
    bash scripts/ci/fetch-contract.sh ) > "$WORK/c9b.fail.log" 2>&1
F5RC=$?
record "refresh_failure_creates_nothing" $([ "$F5RC" -ne 0 ] && [ ! -e "$WORK/c9b/contract/provenance.json" ] && [ ! -e "$WORK/c9b/contract/openapi.json" ] && echo 0 || echo 1) "rc=$F5RC and neither artifact was created"

# ======================= ⑩ 上游非契约提交、字节不变 ========================
section "⑩ 上游出现非契约提交、契约字节不变 → 必须 PASS"
sandbox "$WORK/c10"
refresh_sandbox "$WORK/c10" "$SHA_C1" >/dev/null 2>&1      # provenance.resolvedRef = c1
commit_sandbox "$WORK/c10" 'synced at c1'
PROV_SHA="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["resolvedRef"])' "$WORK/c10/contract/provenance.json" 2>/dev/null || echo missing)"
run_check "$WORK/c10" master                               # 上游 HEAD 已是 c2（非契约提交），契约字节不变
say "   rc=$CHECK_RC  provenance.resolvedRef=$PROV_SHA  今天解析出的上游=$SHA_MASTER"
record "noncontract_commit_snapshot_unchanged_pass" $([ "$CHECK_RC" -eq 0 ] && echo 0 || echo 1) "rc=$CHECK_RC although upstream HEAD moved c1->c2"
record "noncontract_commit_head_really_moved" $([ "$SHA_C1" != "$SHA_MASTER" ] && echo 0 || echo 1) "c1=$SHA_C1 != c2=$SHA_MASTER"
record "provenance_may_lag_head" $([ "$PROV_SHA" = "$SHA_C1" ] && echo 0 || echo 1) "provenance.resolvedRef stayed $SHA_C1 (lag allowed, not compared to today's HEAD)"

# ======================= ⑪ CI 环境下测试旁路被拒 ===========================
section "⑪ CI 环境下把仓内文件当权威源的测试旁路必须被拒（不得静默变绿）"
sandbox "$WORK/c11"
refresh_sandbox "$WORK/c11" v0 >/dev/null 2>&1
commit_sandbox "$WORK/c11" 'synced at v0 (stale vs master)'
# 快照本身是陈旧的；若旁路生效会「自己和自己比」而假绿。
run_check_env "$WORK/c11" master CI=true PIM_CONTRACT_TEST_MODE=1 \
  PIM_CONTRACT_TEST_SOURCE_FILE="$WORK/c11/contract/openapi.json"
say "   CI+bypass rc=$CHECK_RC  tail: $(tail -1 "$CHECK_LOG")"
record "ci_refuses_test_bypass_exit_4" $([ "$CHECK_RC" -eq 4 ] && echo 0 || echo 1) "rc=$CHECK_RC (config error, not a silent pass)"
record "ci_refuses_test_bypass_not_zero" $([ "$CHECK_RC" -ne 0 ] && echo 0 || echo 1) "rc=$CHECK_RC != 0"

run_check_env "$WORK/c11" master GITHUB_ACTIONS=true PIM_CONTRACT_TEST_SOURCE_FILE="$WORK/c11/contract/openapi.json"
record "gha_refuses_source_file_without_test_mode" $([ "$CHECK_RC" -eq 4 ] && echo 0 || echo 1) "rc=$CHECK_RC when GITHUB_ACTIONS=true"

# CI 下用环境变量把「仓内产物路径」指向别的文件也必须被拒（否则等于换掉被校验对象）
run_check_env "$WORK/c11" master CI=true PIM_CONTRACT_SNAPSHOT="$WORK/c11/contract/openapi.json"
record "ci_refuses_snapshot_path_override" $([ "$CHECK_RC" -eq 4 ] && echo 0 || echo 1) "rc=$CHECK_RC with PIM_CONTRACT_SNAPSHOT set under CI"
run_check_env "$WORK/c11" master PIM_CONTRACT_STRICT=1 PIM_CONTRACT_PROVENANCE="$WORK/c11/contract/provenance.json"
record "strict_mode_refuses_provenance_path_override" $([ "$CHECK_RC" -eq 4 ] && echo 0 || echo 1) "rc=$CHECK_RC with PIM_CONTRACT_STRICT=1 and a provenance path override"

# 非 CI 下、且显式声明测试模式时旁路才可用（测试替身自身的正面对照）。
# 这条子调用用 `env -u CI -u GITHUB_ACTIONS` 显式模拟「非 CI 宿主」：GitHub Actions 默认导出
# CI=true / GITHUB_ACTIONS=true，若不在这一条子进程里清掉，守卫会按设计拒绝（rc=4），
# 正面对照在 runner 上必然假红；上面的负面对照不清标记，仍在原环境证明 CI 下旁路被拒。
run_check_env "$WORK/c11" master env -u CI -u GITHUB_ACTIONS PIM_CONTRACT_TEST_MODE=1 \
  PIM_CONTRACT_TEST_SOURCE_FILE="$WORK/c11/contract/openapi.json"
record "test_stub_seam_reachable_only_in_test_mode" $([ "$CHECK_RC" -eq 0 ] && echo 0 || echo 1) "rc=$CHECK_RC with PIM_CONTRACT_TEST_MODE=1 outside CI"

# ======================= ⑫ 真实 CI YAML step + gate 传播 ===================
section "⑫ 原样抽取真实 CI step 与 gate check 函数，证明失败传播"
STEP="$WORK/ci-step-body.sh"
GATE="$WORK/ci-gate-body.sh"
python3 - "$CI_YML" "$STEP" "$GATE" <<'PY'
import sys, yaml
src, step_out, gate_out = sys.argv[1:4]
doc = yaml.safe_load(open(src, encoding="utf-8"))
want = "Verify client contract is up to date"
found = None
for job, spec in (doc.get("jobs") or {}).items():
    for step in (spec.get("steps") or []):
        if step.get("name") == want:
            found = (job, step)
if not found:
    print("step not found", file=sys.stderr); sys.exit(1)
job, step = found
open(step_out, "w", encoding="utf-8").write(step["run"])
# gate
gate = doc["jobs"]["gate"]
body = None
for step in (gate.get("steps") or []):
    if "run" in step:
        body = step["run"]
open(gate_out, "w", encoding="utf-8").write(body or "")
print("extracted step '%s' from job '%s'" % (want, job))
PY
[ -s "$STEP" ] || { record "yaml_step_extracted" 1 "cannot extract CI step body"; }
[ -s "$STEP" ] && record "yaml_step_extracted" 0 "step body extracted verbatim from .github/workflows/ci.yml"

# 结构断言：step 所在 job 被 gate 消费；无 if / continue-on-error / env 覆盖；
# 新 job 先跑行为测试、再跑真实 upstream check；显式 setup-python；release 也依赖本 job。
STRUCT="$(python3 - "$CI_YML" <<'PY'
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
jobs = doc["jobs"]
want = "Verify client contract is up to date"
problems = []
owner = None
for job, spec in jobs.items():
    for step in (spec.get("steps") or []):
        if step.get("name") == want:
            owner = job
            if "if" in step: problems.append("step has if:")
            if step.get("continue-on-error") in (True, "true"): problems.append("step has continue-on-error")
            if step.get("env"): problems.append("step sets env: %s" % sorted(step["env"]))
if owner is None: problems.append("step missing")
else:
    if owner not in (jobs["gate"].get("needs") or []): problems.append("owner job %s not in gate.needs" % owner)
    if "if" in jobs[owner]: problems.append("owner job has if:")
    if jobs[owner].get("continue-on-error") in (True, "true"): problems.append("owner job has continue-on-error")
    # job 内顺序：先行为测试，再只读 check
    runs = [s.get("run", "") for s in jobs[owner].get("steps", []) if "run" in s]
    idx_test = next((i for i, r in enumerate(runs) if "scripts/ci/test-contract-freshness.sh" in r), None)
    idx_check = next((i for i, r in enumerate(runs) if "scripts/ci/check-contract-freshness.sh" in r), None)
    if idx_test is None: problems.append("owner job does not run the behaviour test")
    if idx_check is None: problems.append("owner job does not run the read-only check")
    if idx_test is not None and idx_check is not None and not idx_test < idx_check:
        problems.append("behaviour test does not run before the read-only check")
    uses = " ".join(s.get("uses", "") for s in jobs[owner].get("steps", []))
    if "setup-python" not in uses: problems.append("owner job has no actions/setup-python step")
    if jobs[owner].get("runs-on") != "ubuntu-latest": problems.append("owner job is not ubuntu-latest")
# release 也必须依赖 freshness（master 上 release 只依赖 build 会绕过本 check）
if owner and owner not in (jobs["release"].get("needs") or []): problems.append("release.needs missing %s" % owner)
on = doc.get("on") or doc.get(True) or {}
triggers = sorted(on.keys())
if "push" not in triggers: problems.append("push trigger missing")
if "pull_request" not in triggers: problems.append("pull_request trigger missing")
if "workflow_dispatch" not in on: problems.append("workflow_dispatch trigger missing")
# gate 必须逐个 check 消费 owner job
gate_body = " ".join(s.get("run","") for s in jobs["gate"]["steps"] if "run" in s)
if owner and ('check "%s"' % owner) not in gate_body:
    problems.append("gate does not call check \"%s\"" % owner)
print("|".join(problems) if problems else "OK")
PY
)"
record "ci_step_wiring_ok" $([ "$STRUCT" = "OK" ] && echo 0 || echo 1) "structure: $STRUCT"
say "   CI triggers: $(python3 -c 'import yaml,sys;d=yaml.safe_load(open(sys.argv[1]));print(sorted((d.get("on") or d.get(True)).keys()))' "$CI_YML")"

# 步骤体在三个失败分支都必须非 0（GitHub 用 bash -e -o pipefail 调 run）
step_run() { # step_run <dir> <ref> [raw_base]
  local dir="$1" ref="$2" base="${3:-$RAW}"
  ( cd "$dir" && env PIM_CONTRACT_RAW_BASE="$base" PIM_CONTRACT_REPO=pim-api \
      PIM_CONTRACT_REMOTE="$UPSTREAM_REPO" PIM_CONTRACT_REF="$ref" \
      bash -e -o pipefail "$STEP" ) > "$dir.step.log" 2>&1
  STEP_RC=$?
}
step_run "$WORK/c1" master
record "ci_step_green_on_consistent_repo" $([ "$STEP_RC" -eq 0 ] && echo 0 || echo 1) "rc=$STEP_RC on the consistent sandbox"
step_run "$WORK/c2" master
record "ci_step_red_on_stale_snapshot" $([ "$STEP_RC" -ne 0 ] && echo 0 || echo 1) "rc=$STEP_RC when the snapshot is stale"
step_run "$WORK/c1" master "http://127.0.0.1:1"
record "ci_step_red_on_fetch_failure" $([ "$STEP_RC" -ne 0 ] && echo 0 || echo 1) "rc=$STEP_RC when the source is unreachable"

# gate：把 contract-freshness 置为 failure / skipped，gate 必须非 0（失败传播到 required check）
gate_run() { # gate_run <R_RESOLVE> <R_APK> <R_SHELL> <R_FRESH>
  ( env R_RESOLVE="$1" R_APK="$2" R_SHELL="$3" R_FRESHNESS="$4" bash -e -o pipefail "$GATE" ) > "$WORK/gate.log" 2>&1
  GATE_RC=$?
}
gate_run success success success success
record "gate_green_when_jobs_success" $([ "$GATE_RC" -eq 0 ] && echo 0 || echo 1) "rc=$GATE_RC with R_FRESHNESS=success"
gate_run success success success failure
record "gate_red_when_freshness_failed" $([ "$GATE_RC" -ne 0 ] && echo 0 || echo 1) "rc=$GATE_RC with R_FRESHNESS=failure (freshness failure propagates)"
gate_run success success success skipped
record "gate_red_when_freshness_skipped" $([ "$GATE_RC" -ne 0 ] && echo 0 || echo 1) "rc=$GATE_RC with R_FRESHNESS=skipped"
gate_run success failure success success
record "gate_still_red_when_android_build_failed" $([ "$GATE_RC" -ne 0 ] && echo 0 || echo 1) "rc=$GATE_RC with R_APK=failure (old mandatory jobs still gate)"

# ============================== 汇总 =======================================
say ""
say "== summary: PASS=$PASS FAIL=$FAIL =="
if [ "$FAIL" -eq 0 ]; then
  say "RESULT: 全部断言通过（exit 0）"
  exit 0
fi
say "RESULT: 存在失败断言（exit 1）"
exit 1
