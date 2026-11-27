#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 行为测试：Android release 资产准备与发布后回读。
#
# prepare-release-assets.sh：归一化 APK、生成 version.json、校验
#   tag/commit/run/versionCode/displayVersion/sha256/size。
# verify-published-release.sh：回读远端 release/资产/latest，失败即红。
# 全部离线：APK 是固定小夹具，gh 是 stub。
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PREPARE="$ROOT/scripts/ci/prepare-release-assets.sh"
VERIFY="$ROOT/scripts/ci/verify-published-release.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-android-release-assets.XXXXXX")"
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
        case "${FAKE_RELEASE_MODE:-json}" in
          json) cat "${FAKE_RELEASE_JSON:?FAKE_RELEASE_JSON required}" ;;
          error) echo "gh: Server Error (HTTP 500)" >&2; exit 1 ;;
          *) echo "stub gh: unknown FAKE_RELEASE_MODE" >&2; exit 2 ;;
        esac
        ;;
      */releases/latest)
        case "${FAKE_LATEST_MODE:-none}" in
          none) not_found ;;
          error) echo "gh: Server Error (HTTP 500)" >&2; exit 1 ;;
          json) cat "${FAKE_LATEST_JSON:?FAKE_LATEST_JSON required}" ;;
          *) echo "stub gh: unknown FAKE_LATEST_MODE" >&2; exit 2 ;;
        esac
        ;;
      *) echo "stub gh: unsupported api $path" >&2; exit 2 ;;
    esac
    ;;
  release)
    sub="${1:-}"; shift || true
    if [[ "$sub" != "download" ]]; then
      echo "stub gh: unsupported release $sub" >&2
      exit 2
    fi
    pattern=""
    dir=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --pattern) pattern="$2"; shift 2 ;;
        --dir) dir="$2"; shift 2 ;;
        --repo) shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -n "$pattern" && -n "$dir" ]] || { echo "stub gh: pattern/dir required" >&2; exit 2; }
    if [[ ! -f "${FAKE_REMOTE_ASSETS:?FAKE_REMOTE_ASSETS required}/$pattern" ]]; then
      echo "gh: release asset not found: $pattern" >&2
      exit 1
    fi
    cp "${FAKE_REMOTE_ASSETS}/$pattern" "$dir/$pattern"
    ;;
  *) echo "stub gh: unsupported command $cmd" >&2; exit 2 ;;
esac
STUB
chmod +x "$TMP/bin/gh"
export PATH="$TMP/bin:$PATH"
export TMP

SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
TAG="android-v26.11.1042"
VERSION="26.11.1042"
VC=101042
DISPLAY="1.1.0"
RUN_ID=999
RUN_NUMBER=42

make_dl() { # <dir>
  local dl="$1"
  mkdir -p "$dl/pim-android-v26.11.1042-vc101042" \
    "$dl/pim-shell-android-v26.11.1042-vc101042"
  printf 'PK\003\004android-apk-payload' > "$dl/pim-android-v26.11.1042-vc101042/pim-android.apk"
  printf 'PK\003\004shell-apk-payload' > "$dl/pim-shell-android-v26.11.1042-vc101042/pim-shell.apk"
}

run_prepare() { # <label> <want> <dl-dir> <out-dir> [extra args...]
  local label="$1" want="$2" dl="$3" out="$4"; shift 4
  local rc=0
  bash "$PREPARE" \
    --repo 2746267826/pim-android --tag "$TAG" \
    --version "$VERSION" --version-code "$VC" --display-version "$DISPLAY" \
    --sha "$SHA" --run-id "$RUN_ID" --run-number "$RUN_NUMBER" \
    --download-dir "$dl" --out-dir "$out" "$@" > "$TMP/prepare.out" 2>&1 || rc=$?
  if [[ "$want" == "pass" ]]; then
    if [[ "$rc" -eq 0 ]]; then ok "$label"; else bad "$label（rc=$rc）：$(cat "$TMP/prepare.out")"; fi
  else
    if [[ "$rc" -eq 0 ]]; then bad "$label：期望失败，实际通过"; elif ! grep -q "$want" "$TMP/prepare.out"; then bad "$label：期望错误包含 [$want]，实际：$(cat "$TMP/prepare.out")"; else ok "$label"; fi
  fi
}

echo "== prepare-release-assets.sh =="

DL_GOOD="$TMP/dl-good"
make_dl "$DL_GOOD"
OUT_GOOD="$TMP/out-good"
run_prepare "完整资产 → 准备成功" pass "$DL_GOOD" "$OUT_GOOD"
if jq -e --arg sha "$SHA" --arg tag "$TAG" --arg display "$DISPLAY" \
     '(.gitSha==$sha and .tag==$tag and .displayVersion==$display and
      .version=="26.11.1042" and .versionCode==101042 and
      .runId==999 and .runNumber==42 and
      (.assets.android.sha256|length)==64)' "$OUT_GOOD/version.json" >/dev/null; then
  ok "version.json 绑定 tag/commit/run/display/versionCode"
else
  bad "version.json 关键字段缺失：$(cat "$OUT_GOOD/version.json")"
fi
[[ -f "$OUT_GOOD/pim-android-v26.11.1042.apk" && -f "$OUT_GOOD/pim-shell-android-v26.11.1042.apk" ]] \
  && ok "APK 名称已归一化" || bad "APK 名称未归一化"

DL_NO_SHELL="$TMP/dl-no-shell"
make_dl "$DL_NO_SHELL"
rm -f "$DL_NO_SHELL"/pim-shell-android-*/*.apk
run_prepare "缺 shell APK → 失败" "恰好 1 个" "$DL_NO_SHELL" "$TMP/out-no-shell"

DL_TWO_ANDROID="$TMP/dl-two-android"
make_dl "$DL_TWO_ANDROID"
mkdir -p "$DL_TWO_ANDROID/pim-android-extra"
cp "$DL_TWO_ANDROID"/pim-android-*/*.apk "$DL_TWO_ANDROID/pim-android-extra/extra.apk"
run_prepare "同一构建出现两个 android APK → 失败（不能静默挑一个）" "恰好 1 个" "$DL_TWO_ANDROID" "$TMP/out-two"

run_prepare "versionCode 与 tag 推导不符 → 失败" "versionCode" "$DL_GOOD" "$TMP/out-bad-vc" --version-code 101041
run_prepare "run number 与 tag（+1000 补偿）不符 → 失败" "run number" "$DL_GOOD" "$TMP/out-bad-run" --run-number 41
run_prepare "version 与 tag 机器版本不符 → 失败" "version 核心" "$DL_GOOD" "$TMP/out-bad-version" --version 26.11.1041

DL_NOT_ZIP="$TMP/dl-not-zip"
make_dl "$DL_NOT_ZIP"
printf 'not a zip' > "$DL_NOT_ZIP"/pim-android-*/*.apk
run_prepare "APK 不是 ZIP → 失败" "不是 ZIP/APK" "$DL_NOT_ZIP" "$TMP/out-not-zip"

echo "== verify-published-release.sh =="

make_release_json() { # <file> <id> <tag> <target> <pre> <draft> <assets-dir>
  local file="$1" id="$2" tag="$3" target="$4" pre="$5" draft="$6" assets="$7"
  local a="pim-android-v26.11.1042.apk"
  local s="pim-shell-android-v26.11.1042.apk"
  jq -n \
    --argjson id "$id" --arg tag "$tag" --arg target "$target" \
    --argjson pre "$pre" --argjson draft "$draft" \
    --arg a "$a" \
    --arg s "$s" \
    --argjson asz "$(wc -c < "$assets/$a" | tr -d ' ')" \
    --argjson ssz "$(wc -c < "$assets/$s" | tr -d ' ')" \
    --argjson vsz "$(wc -c < "$assets/version.json" | tr -d ' ')" \
    '{id:$id,tag_name:$tag,target_commitish:$target,draft:$draft,prerelease:$pre,
      assets:[{name:$a,size:$asz},{name:$s,size:$ssz},{name:"version.json",size:$vsz}]}' \
    > "$file"
}

run_verify() { # <label> <want> <release-json> <remote-assets> [extra args...]
  local label="$1" want="$2" release="$3" remote="$4"; shift 4
  local rc=0
  env PIM_LATEST_CONFIRM_SECONDS=0 \
    FAKE_RELEASE_JSON="$release" FAKE_REMOTE_ASSETS="$remote" \
    FAKE_LATEST_MODE="${FAKE_LATEST_MODE:-none}" FAKE_LATEST_JSON="${FAKE_LATEST_JSON:-}" \
    bash "$VERIFY" \
      --repo 2746267826/pim-android --tag "$TAG" --sha "$SHA" \
      --version "$VERSION" --tag-machine-version "$VERSION" \
      --version-code "$VC" --display-version "$DISPLAY" \
      --run-id "$RUN_ID" --run-number "$RUN_NUMBER" \
      --expect-prerelease true --release-id 500 "$@" \
      > "$TMP/verify.out" 2>&1 || rc=$?
  if [[ "$want" == "pass" ]]; then
    if [[ "$rc" -eq 0 ]]; then ok "$label"; else bad "$label（rc=$rc）：$(cat "$TMP/verify.out")"; fi
  else
    if [[ "$rc" -eq 0 ]]; then bad "$label：期望失败，实际通过"; elif ! grep -q "$want" "$TMP/verify.out"; then bad "$label：期望错误包含 [$want]，实际：$(cat "$TMP/verify.out")"; else ok "$label"; fi
  fi
}

REL_GOOD="$TMP/release-good.json"
make_release_json "$REL_GOOD" 500 "$TAG" "$SHA" true false "$OUT_GOOD"
run_verify "发布后回读 release/资产全部一致 → 通过" pass "$REL_GOOD" "$OUT_GOOD"

REL_WRONG_TARGET="$TMP/release-wrong-target.json"
make_release_json "$REL_WRONG_TARGET" 500 "$TAG" "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" true false "$OUT_GOOD"
run_verify "target_commitish 不一致 → 失败" "target_commitish" "$REL_WRONG_TARGET" "$OUT_GOOD"

REL_FORMAL="$TMP/release-formal.json"
make_release_json "$REL_FORMAL" 500 "$TAG" "$SHA" false false "$OUT_GOOD"
run_verify "期望 pre-release 但已是正式版 → 失败" "prerelease" "$REL_FORMAL" "$OUT_GOOD"

REL_MISSING_ASSET="$TMP/release-missing-asset.json"
jq '.assets = [.assets[] | select(.name != "pim-shell-android-v26.11.1042.apk")]' "$REL_GOOD" > "$REL_MISSING_ASSET"
run_verify "远端缺 shell APK → 失败" "资产不符合 Android 形态" "$REL_MISSING_ASSET" "$OUT_GOOD"

REL_EXTRA_ASSET="$TMP/release-extra-asset.json"
jq '.assets += [{name:"extra.txt",size:1}]' "$REL_GOOD" > "$REL_EXTRA_ASSET"
run_verify "远端多出额外资产 → 失败" "资产不符合 Android 形态" "$REL_EXTRA_ASSET" "$OUT_GOOD"

REMOTE_BAD="$TMP/remote-bad"
cp -a "$OUT_GOOD" "$REMOTE_BAD"
printf 'PK\003\004tampered-apk-payloa' > "$REMOTE_BAD/pim-android-v26.11.1042.apk"
run_verify "远端 APK 内容被替换 → sha256/size 失败" "sha256" "$REL_GOOD" "$REMOTE_BAD"

REMOTE_LOCAL_MISMATCH="$TMP/remote-local-mismatch"
cp -a "$OUT_GOOD" "$REMOTE_LOCAL_MISMATCH"
printf 'different-version-json' > "$REMOTE_LOCAL_MISMATCH/version.json"
run_verify "远端 version.json 与本地待上传文件不一致 → 失败" "不一致" "$REL_GOOD" "$REMOTE_LOCAL_MISMATCH" \
  --assets-dir "$OUT_GOOD"

REL_NONNUMERIC="$TMP/release-nonnumeric.json"
jq '.id = "RE_kwDOtest"' "$REL_GOOD" > "$REL_NONNUMERIC"
run_verify "release id 不是 REST 数字 id → 失败" "REST 数字 id" "$REL_NONNUMERIC" "$OUT_GOOD"

LATEST_GOOD="$TMP/latest-good.json"
jq -n --argjson id 500 --arg tag "$TAG" \
  '{id:$id,tag_name:$tag,prerelease:false,draft:false,target_commitish:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}' \
  > "$LATEST_GOOD"
rc=0
env PIM_LATEST_CONFIRM_SECONDS=0 FAKE_RELEASE_JSON="$REL_FORMAL" FAKE_REMOTE_ASSETS="$OUT_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$LATEST_GOOD" \
  bash "$VERIFY" --repo 2746267826/pim-android --tag "$TAG" --sha "$SHA" \
  --version "$VERSION" --tag-machine-version "$VERSION" --version-code "$VC" \
  --display-version "$DISPLAY" --run-id "$RUN_ID" --run-number "$RUN_NUMBER" \
  --expect-prerelease false --expect-latest true --release-id 500 > "$TMP/verify-latest.out" 2>&1 || rc=$?
if [[ "$rc" -eq 0 ]]; then ok "latest 指向同一 release（显式 json）→ 通过"; else bad "latest 指向同一 release → 失败（rc=$rc）：$(cat "$TMP/verify-latest.out")"; fi

LATEST_WRONG="$TMP/latest-wrong.json"
jq -n --argjson id 501 --arg tag "android-v26.11.1043" \
  '{id:$id,tag_name:$tag,prerelease:false,draft:false}' > "$LATEST_WRONG"
rc=0
env PIM_LATEST_CONFIRM_SECONDS=0 FAKE_RELEASE_JSON="$REL_FORMAL" FAKE_REMOTE_ASSETS="$OUT_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$LATEST_WRONG" \
  bash "$VERIFY" --repo 2746267826/pim-android --tag "$TAG" --sha "$SHA" \
  --version "$VERSION" --tag-machine-version "$VERSION" --version-code "$VC" \
  --display-version "$DISPLAY" --run-id "$RUN_ID" --run-number "$RUN_NUMBER" \
  --expect-prerelease false --expect-latest true --release-id 500 > "$TMP/verify-latest-wrong.out" 2>&1 || rc=$?
if [[ "$rc" -ne 0 && "$(cat "$TMP/verify-latest-wrong.out")" == *"未收敛"* ]]; then ok "latest 指向别的 release → 失败"; else bad "latest 指向别的 release → 失败（rc=$rc）：$(cat "$TMP/verify-latest-wrong.out")"; fi

rc=0
env PIM_LATEST_CONFIRM_SECONDS=0 FAKE_RELEASE_JSON="$REL_FORMAL" FAKE_REMOTE_ASSETS="$OUT_GOOD" \
  FAKE_LATEST_MODE=error \
  bash "$VERIFY" --repo 2746267826/pim-android --tag "$TAG" --sha "$SHA" \
  --version "$VERSION" --tag-machine-version "$VERSION" --version-code "$VC" \
  --display-version "$DISPLAY" --run-id "$RUN_ID" --run-number "$RUN_NUMBER" \
  --expect-prerelease false --expect-latest true --release-id 500 > "$TMP/verify-latest-error.out" 2>&1 || rc=$?
if [[ "$rc" -ne 0 && "$(cat "$TMP/verify-latest-error.out")" == *"fail-closed"* ]]; then ok "latest API 500 → fail-closed"; else bad "latest API 500 → fail-closed（rc=$rc）：$(cat "$TMP/verify-latest-error.out")"; fi

if [[ "$fail" -ne 0 ]]; then
  echo "release-assets tests failed: ${fail} failed, ${pass} passed" >&2
  exit 1
fi
echo "release-assets tests passed (${pass} checks)"
