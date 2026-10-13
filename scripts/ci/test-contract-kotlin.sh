#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 契约 Kotlin 生成物一致性门禁的**行为测试**（离线；不联网、不跑 gradle）。
#
# 为什么要有它：门禁不能是「永远 PASS 的摆设」。这里在临时目录里搭一个假仓库，
# 逐条证明 check-contract-kotlin.sh 会红：
#   1) 原样 → 绿（否则门禁只会制造噪音）；
#   2) 手工改了生成物 → 红；
#   3) 契约快照变了、生成物没重新生成（加了字段）→ 红；
#   4) 契约快照变了、生成物没重新生成（加了 operation 的字段依赖）→ 红；
#   5) 生成物缺失 → 红；
#   6) operation 清单里写了契约中不存在的端点 → 生成器拒绝产出（配置错误，非静默变绿）。
#
# 用法：
#   bash scripts/ci/test-contract-kotlin.sh
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

GENERATED_REL="src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt"

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pim-contract-kotlin-test.XXXXXX")"
cleanup() { rm -rf "$TMP_ROOT"; }
trap cleanup EXIT

PASSED=0
FAILED=0

log()  { printf '%s\n' "$*"; }
ok()   { PASSED=$((PASSED + 1)); log "  ✅ $*"; }
bad()  { FAILED=$((FAILED + 1)); log "  ❌ $*"; }

# 搭一个只含门禁所需文件的「假仓库」：脚本按自身位置推导 ROOT，因此可以直接整仓复制子集。
make_fixture() { # <名称> -> 打印路径
  local name="$1"
  local dir="$TMP_ROOT/$name"
  mkdir -p "$dir/scripts/ci" "$dir/scripts/contract" "$dir/contract" "$(dirname "$dir/$GENERATED_REL")"
  cp "$ROOT/scripts/ci/check-contract-kotlin.sh" "$dir/scripts/ci/"
  cp "$ROOT/scripts/ci/generate-contract-kotlin.sh" "$dir/scripts/ci/"
  cp "$ROOT/scripts/contract/generate-kotlin.py" "$dir/scripts/contract/"
  cp "$ROOT/scripts/contract/client-operations.json" "$dir/scripts/contract/"
  cp "$ROOT/contract/openapi.json" "$dir/contract/"
  cp "$ROOT/$GENERATED_REL" "$dir/$GENERATED_REL"
  printf '%s' "$dir"
}

run_check() { # <fixture> -> 打印 rc
  local dir="$1"
  bash "$dir/scripts/ci/check-contract-kotlin.sh" >"$dir/check.log" 2>&1
  printf '%s' "$?"
}

expect_rc() { # <期望 rc> <实际 rc> <说明> <fixture>
  local expected="$1" actual="$2" what="$3" dir="$4"
  if [ "$actual" = "$expected" ]; then
    ok "$what（rc=$actual，符合预期）"
  else
    bad "$what：期望 rc=$expected，实际 rc=$actual"
    sed -n '1,40p' "$dir/check.log" | sed 's/^/      | /'
  fi
}

command -v python3 >/dev/null 2>&1 || { log "需要 python3 才能跑本测试。"; exit 2; }

log "== 契约 Kotlin 生成物门禁 · 行为测试（离线 fixture） =="
log ""

# --- 1) 原样 → 绿 ----------------------------------------------------------
log "[1] 假仓库原样（生成物与契约一致）"
F="$(make_fixture clean)"; rc="$(run_check "$F")"
expect_rc 0 "$rc" "一致时通过" "$F"

# --- 2) 手工改生成物 → 红 --------------------------------------------------
log "[2] 手工改生成物（加了契约里没有的字段）"
F="$(make_fixture hand-edit)"
python3 - "$F/$GENERATED_REL" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
s = s.replace("    val title: String,", "    val title: String,\n    val fabricatedField: String? = null,", 1)
p.write_text(s, encoding="utf-8")
PY
rc="$(run_check "$F")"
expect_rc 1 "$rc" "被手工改动的生成物会被逐字节比对抓出来" "$F"

# --- 3) 契约快照改了、生成物没重新生成（字段）→ 红 --------------------------
log "[3] 契约快照新增字段、生成物未重新生成"
F="$(make_fixture contract-field-change)"
python3 - "$F/contract/openapi.json" <<'PY'
import json, sys, pathlib
p = pathlib.Path(sys.argv[1])
d = json.loads(p.read_text(encoding="utf-8"))
d["components"]["schemas"]["EventResponse"]["properties"]["newContractField"] = {"type": "string"}
p.write_text(json.dumps(d, indent=1, ensure_ascii=False), encoding="utf-8")
PY
rc="$(run_check "$F")"
expect_rc 1 "$rc" "契约变了而生成物没跟上会被拦下" "$F"

# --- 4) 契约快照的 required 变了、生成物没重新生成 → 红 ---------------------
log "[4] 契约快照把可选字段改成必填、生成物未重新生成"
F="$(make_fixture contract-required-change)"
python3 - "$F/contract/openapi.json" <<'PY'
import json, sys, pathlib
p = pathlib.Path(sys.argv[1])
d = json.loads(p.read_text(encoding="utf-8"))
schema = d["components"]["schemas"]["MobileTimelineResponse"]
required = schema.setdefault("required", [])
if "page" not in required:
    required.append("page")
p.write_text(json.dumps(d, indent=1, ensure_ascii=False), encoding="utf-8")
PY
rc="$(run_check "$F")"
expect_rc 1 "$rc" "可空性变化同样会被拦下" "$F"

# --- 5) 生成物缺失 → 红 ----------------------------------------------------
log "[5] 生成物缺失"
F="$(make_fixture missing-artifact)"
rm -f "$F/$GENERATED_REL"
rc="$(run_check "$F")"
expect_rc 1 "$rc" "生成物不在仓里属于不一致" "$F"

# --- 6) operation 清单写了契约里没有的端点 → 生成器拒绝 ---------------------
log "[6] operation 清单写了契约里不存在的端点"
F="$(make_fixture fabricated-operation)"
python3 - "$F/scripts/contract/client-operations.json" <<'PY'
import json, sys, pathlib
p = pathlib.Path(sys.argv[1])
d = json.loads(p.read_text(encoding="utf-8"))
d["operations"].append({"apiService": "pcWhatever", "method": "GET", "path": "/api/v1/pc/whatever"})
p.write_text(json.dumps(d, indent=2, ensure_ascii=False), encoding="utf-8")
PY
rc="$(run_check "$F")"
expect_rc 4 "$rc" "编造的 operation 让生成器直接报错（不是静默产出半张契约）" "$F"

# --- 7) 门禁确实接进了必需检查链（否则「红了也没人拦」） --------------------
log "[7] CI 必需检查链接线"
if ! command -v python3 >/dev/null 2>&1 || ! python3 -c "import yaml" >/dev/null 2>&1; then
  bad "需要 PyYAML 才能校验 CI 接线（本项无法执行，不算通过）"
else
  wiring="$(python3 - "$ROOT/.github/workflows/ci.yml" <<'PY'
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
jobs = doc.get("jobs", {})
problems = []
job = jobs.get("contract-codegen")
if job is None:
    problems.append("ci.yml 缺少 contract-codegen job")
else:
    steps = "\n".join(str(s.get("run", "")) for s in job.get("steps", []))
    for required in ("scripts/ci/test-contract-kotlin.sh", "scripts/ci/check-contract-kotlin.sh"):
        if required not in steps:
            problems.append(f"contract-codegen job 没有调用 {required}")
gate_needs = jobs.get("gate", {}).get("needs", [])
if "contract-codegen" not in gate_needs:
    problems.append("CI Gate 的 needs 里没有 contract-codegen")
gate_run = "\n".join(str(s.get("run", "")) for s in jobs.get("gate", {}).get("steps", []))
if 'check "contract-codegen"' not in gate_run:
    problems.append("CI Gate 没有把 contract-codegen 纳入逐个 check")
release_needs = jobs.get("release", {}).get("needs", [])
if "contract-codegen" not in release_needs:
    problems.append("release 的 needs 里没有 contract-codegen（会在门禁红时照样发版）")
print("OK" if not problems else "|".join(problems))
PY
)"
  if [ "$wiring" = "OK" ]; then
    ok "contract-codegen 是必需检查（gate needs + 逐个 check + release needs）"
  else
    bad "CI 接线不对：$wiring"
  fi
fi

log ""
log "== 结果：通过 $PASSED 项，失败 $FAILED 项 =="
if [ "$FAILED" -ne 0 ]; then
  exit 1
fi
