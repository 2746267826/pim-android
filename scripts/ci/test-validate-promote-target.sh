#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 行为测试：Android 正式版提升来源校验（validate-promote-target.sh）。
#
# 重点：tag 不能只凭名字提升。必须同时证明
#   * tag 指向的 commit 在当前 master 历史里，且 commit/HEAD 的 VERSION 正确；
#   * tag 里的 run 号（去掉 +1000 历史补偿）能在该 commit 上找到成功的 master
#     push 构建，且 run 的 headSha / branch / conclusion 都匹配；
#   * release targetCommitish 精确等于 commit SHA；
#   * 资产恰好是 Android APK ×2 + version.json，version.json 绑定
#     tag/commit/run/显示版本/versionCode，APK 的 sha256/size 也匹配；
#   * 不会把 /releases/latest 指回旧 CalVer 或低显示版本。
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/ci/validate-promote-target.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-android-promote-target.XXXXXX")"
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
  release)
    sub="${1:-}"; shift || true
    case "$sub" in
      view)
        fields=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --json) fields="$2"; shift 2 ;;
            *) shift ;;
          esac
        done
        IFS=',' read -ra FIELDS <<< "$fields"
        for f in "${FIELDS[@]}"; do
          case "$f" in
            apiUrl|assets|author|body|createdAt|databaseId|id|isDraft|isImmutable|isPrerelease|name|publishedAt|tagName|tarballUrl|targetCommitish|uploadUrl|url|zipballUrl) ;;
            *) echo "Unknown JSON field: \"$f\"" >&2; exit 1 ;;
          esac
        done
        cat "${FAKE_RELEASE_JSON:?FAKE_RELEASE_JSON required}"
        ;;
      download)
        pattern=""
        dir=""
        while [[ $# -gt 0 ]]; do
          case "$1" in
            --pattern) pattern="$2"; shift 2 ;;
            --dir) dir="$2"; shift 2 ;;
            --repo|--clobber) shift 2 ;;
            *) shift ;;
          esac
        done
        [[ -n "$pattern" && -n "$dir" ]] || { echo "stub gh: pattern/dir required" >&2; exit 2; }
        if [[ ! -f "${FAKE_ASSET_SOURCE:?FAKE_ASSET_SOURCE required}/$pattern" ]]; then
          echo "gh: release asset not found: $pattern" >&2
          exit 1
        fi
        cp "${FAKE_ASSET_SOURCE}/$pattern" "$dir/$pattern"
        ;;
      *) echo "stub gh: unsupported release $sub" >&2; exit 2 ;;
    esac
    ;;
  api)
    path="${1:-}"
    case "$path" in
      */releases/latest)
        case "${FAKE_LATEST_MODE:-none}" in
          none) not_found ;;
          error) echo "gh: Server Error (HTTP 500)" >&2; exit 1 ;;
          json) cat "${FAKE_LATEST_JSON:?FAKE_LATEST_JSON required}" ;;
          *) echo "stub gh: unknown FAKE_LATEST_MODE" >&2; exit 2 ;;
        esac
        ;;
      */git/ref/tags/*)
        if [[ "${FAKE_TAG_TYPE:-commit}" == "tag" ]]; then
          jq -n --arg sha "${FAKE_ANNOTATED_SHA:-aaaa}" '{object:{type:"tag",sha:$sha}}'
        else
          jq -n --arg sha "${FAKE_TAG_SHA:?FAKE_TAG_SHA required}" '{object:{type:"commit",sha:$sha}}'
        fi
        ;;
      */git/tags/*)
        jq -n --arg sha "${FAKE_TAG_SHA:?FAKE_TAG_SHA required}" '{object:{type:"commit",sha:$sha}}'
        ;;
      *) echo "stub gh: unsupported api $path" >&2; exit 2 ;;
    esac
    ;;
  run)
    sub="${1:-}"; shift || true
    case "$sub" in
      list) cat "${FAKE_RUNS_JSON:?FAKE_RUNS_JSON required}" ;;
      view) cat "${FAKE_RUN_DETAIL:?FAKE_RUN_DETAIL required}" ;;
      *) echo "stub gh: unsupported run $sub" >&2; exit 2 ;;
    esac
    ;;
  *) echo "stub gh: unsupported command $cmd" >&2; exit 2 ;;
esac
STUB
chmod +x "$TMP/bin/gh"
export PATH="$TMP/bin:$PATH"
export TMP

make_repo() { # <dir> <version>
  git init -q "$1"
  git -C "$1" config user.email test@example.com
  git -C "$1" config user.name test
  printf '%s\n' "$2" > "$1/VERSION"
  git -C "$1" add VERSION
  git -C "$1" commit -q -m "init"
}

make_assets() { # <dir> <sha> <display> <version> <run-id> <run-number>
  local dir="$1" sha="$2" display="$3" version="$4" run_id="$5" run_number="$6"
  local android="pim-android-v${version}.apk"
  local shell="pim-shell-android-v${version}.apk"
  mkdir -p "$dir"
  printf 'android-apk-payload' > "$dir/$android"
  printf 'shell-apk-payload' > "$dir/$shell"
  local as ss asz ssz vc
  as="$(sha256sum "$dir/$android" | awk '{print $1}')"
  ss="$(sha256sum "$dir/$shell" | awk '{print $1}')"
  asz="$(wc -c < "$dir/$android" | tr -d ' ')"
  ssz="$(wc -c < "$dir/$shell" | tr -d ' ')"
  vc=$(( 100000 + 10#${run_number} + 1000 ))
  jq -n \
    --arg version "$version" --argjson vc "$vc" --arg display "$display" \
    --arg tag "android-v${version}" --arg sha "$sha" --argjson runId "$run_id" \
    --argjson runNumber "$run_number" \
    --arg an "$android" --arg as "$as" --argjson asz "$asz" \
    --arg sn "$shell" --arg ss "$ss" --argjson ssz "$ssz" \
    '{
      version:$version, versionCode:$vc, displayVersion:$display,
      tag:$tag, gitSha:$sha, runId:$runId, runNumber:$runNumber,
      releasedAt:"2026-11-26T00:00:00Z",
      assets:{
        android:{version:$version,url:("https://github.com/2746267826/pim-android/releases/download/"+$tag+"/"+$an),sha256:$as,size:$asz},
        shellAndroid:{version:$version,url:("https://github.com/2746267826/pim-android/releases/download/"+$tag+"/"+$sn),sha256:$ss,size:$ssz}
      }
    }' > "$dir/version.json"
}

make_release() { # <file> <id> <tag> <target> <pre> <draft> <assets-json>
  jq -n \
    --argjson id "$2" --arg tag "$3" --arg target "$4" \
    --argjson pre "$5" --argjson draft "$6" --argjson assets "$7" \
    '{databaseId:$id,tagName:$tag,isPrerelease:$pre,isDraft:$draft,targetCommitish:$target,assets:$assets}' \
    > "$1"
}

assets_json() { # <version>
  jq -n --arg a "pim-android-v${1}.apk" --arg s "pim-shell-android-v${1}.apk" \
    '[{name:$a,size:19},{name:$s,size:17},{name:"version.json",size:1}]'
}

run_case() { # <label> <pass|pattern> <out-file> <repo-dir> [env...]
  local label="$1" want="$2" out="$3" repo="$4"; shift 4
  local rc=0
  (
    cd "$repo"
    env "${BASE_ENV[@]}" "$@" bash "$SCRIPT" \
      --repo 2746267826/pim-android --tag android-v26.11.1042 --version 1.1.0
  ) > "$out" 2>&1 || rc=$?
  if [[ "$want" == "pass" ]]; then
    if [[ "$rc" -eq 0 ]]; then ok "$label"; else bad "$label（rc=$rc）：$(cat "$out")"; fi
  else
    if [[ "$rc" -eq 0 ]]; then
      bad "$label：期望失败，实际通过"
    elif ! grep -q "$want" "$out"; then
      bad "$label：期望错误包含 [$want]，实际：$(cat "$out")"
    else
      ok "$label"
    fi
  fi
}

echo "== validate-promote-target.sh =="

REPO_GOOD="$TMP/repo-good"
make_repo "$REPO_GOOD" 1.1.0
SHA_GOOD="$(git -C "$REPO_GOOD" rev-parse HEAD)"
ASSETS_GOOD="$TMP/assets-good"
make_assets "$ASSETS_GOOD" "$SHA_GOOD" "1.1.0" "26.11.1042" 999 42
RELEASE_GOOD="$TMP/release-good.json"
make_release "$RELEASE_GOOD" 500 "android-v26.11.1042" "$SHA_GOOD" true false "$(assets_json 26.11.1042)"
RUNS_GOOD="$TMP/runs-good.json"
jq -n --arg sha "$SHA_GOOD" \
  '[{databaseId:999,event:"push",headBranch:"master",headSha:$sha,conclusion:"success",number:42,status:"completed"}]' \
  > "$RUNS_GOOD"
RUN_DETAIL_GOOD="$TMP/run-detail-good.json"
jq -n --arg sha "$SHA_GOOD" \
  '{databaseId:999,event:"push",headBranch:"master",headSha:$sha,number:42,status:"completed",conclusion:"success"}' \
  > "$RUN_DETAIL_GOOD"

BASE_ENV=(
  FAKE_RELEASE_JSON="$RELEASE_GOOD"
  FAKE_ASSET_SOURCE="$ASSETS_GOOD"
  FAKE_TAG_TYPE=commit
  FAKE_TAG_SHA="$SHA_GOOD"
  FAKE_RUNS_JSON="$RUNS_GOOD"
  FAKE_RUN_DETAIL="$RUN_DETAIL_GOOD"
  FAKE_LATEST_MODE=none
)

run_case "完整来源证明（无 latest）→ 通过" pass "$TMP/out-good" "$REPO_GOOD"
if grep -q '^release_id=500$' "$TMP/out-good" \
   && grep -q '^run_id=999$' "$TMP/out-good" \
   && grep -q '^version=26.11.1042$' "$TMP/out-good" \
   && grep -q '^tag=android-v26.11.1042$' "$TMP/out-good" \
   && grep -q '^is_latest=false$' "$TMP/out-good"; then
  ok "输出 release_id / run_id / version / tag / is_latest=false"
else
  bad "输出缺少关键字段：$(cat "$TMP/out-good")"
fi

write_latest() { # <file> <id> <tag> <title> <target> [body]
  jq -n \
    --argjson id "$2" --arg tag "$3" --arg name "$4" --arg target "$5" --arg body "${6:-}" \
    '{id:$id,tag_name:$tag,prerelease:false,draft:false,target_commitish:$target,name:$name,body:$body}' \
    > "$1"
}

write_latest "$TMP/latest-same.json" 500 "android-v26.11.1042" \
  "PIM Android v1.1.0 (android-v26.11.1042)" "$SHA_GOOD"
run_case "目标就是当前 latest 的正式版重入 → 通过" pass "$TMP/out-formal" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-same.json"
if grep -q '^is_latest=true$' "$TMP/out-formal"; then ok "重入输出 is_latest=true"; else bad "重入缺少 is_latest=true：$(cat "$TMP/out-formal")"; fi

write_latest "$TMP/latest-older.json" 400 "android-v26.11.1041" \
  "PIM Android v1.1.0 (android-v26.11.1041)" "$SHA_GOOD"
run_case "同显示版本的更新 CalVer → 允许前进" pass "$TMP/out-newer" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-older.json"

write_latest "$TMP/latest-body.json" 400 "android-v26.11.1041" \
  "android-v26.11.1041" "$SHA_GOOD" "显示版本 / display version: \`1.1.0\`"
run_case "latest 从正文显示版本解析 → 允许前进" pass "$TMP/out-body-version" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-body.json"

write_latest "$TMP/latest-newer.json" 501 "android-v26.11.1043" \
  "PIM Android v1.1.0 (android-v26.11.1043)" "$SHA_GOOD"
run_case "同显示版本但 CalVer 更旧 → 拒绝回退 latest" "回退" "$TMP/out-older" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-newer.json"

write_latest "$TMP/latest-higher-display.json" 410 "android-v26.11.1041" \
  "PIM Android v1.2.0 (android-v26.11.1041)" "$SHA_GOOD"
run_case "新 CalVer 但显示版本更低 → 拒绝回退" "显示版本" "$TMP/out-display" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-higher-display.json"

write_latest "$TMP/latest-unparsable.json" 410 "latest-2026" \
  "PIM Android v1.1.0" "$SHA_GOOD"
run_case "latest tag 无法解析 CalVer → fail-closed" "CalVer" "$TMP/out-unparsable" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-unparsable.json"

run_case "/releases/latest API 错误 → fail-closed" "fail-closed" "$TMP/out-latest-error" "$REPO_GOOD" \
  FAKE_LATEST_MODE=error

printf '%s\n' '{"id": 1}' > "$TMP/latest-incomplete.json"
run_case "/releases/latest 对象不完整 → fail-closed" "不完整" "$TMP/out-latest-incomplete" "$REPO_GOOD" \
  FAKE_LATEST_MODE=json FAKE_LATEST_JSON="$TMP/latest-incomplete.json"

# ---- release 元数据 ----
jq '.isDraft = true' "$RELEASE_GOOD" > "$TMP/release-draft.json"
run_case "release 仍是 draft → 拒绝" "draft" "$TMP/out-draft" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-draft.json"

jq '.databaseId = "RE_kwDOtest"' "$RELEASE_GOOD" > "$TMP/release-graphql-id.json"
run_case "release id 不是 REST 数字 id → 拒绝" "databaseId" "$TMP/out-id" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-graphql-id.json"

jq '.targetCommitish = "master"' "$RELEASE_GOOD" > "$TMP/release-target-branch.json"
run_case "targetCommitish 是分支名 → 拒绝" "targetCommitish" "$TMP/out-target-branch" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-target-branch.json"

jq '.targetCommitish = "1111111111111111111111111111111111111111"' "$RELEASE_GOOD" \
  > "$TMP/release-target-other.json"
run_case "targetCommitish 指向别的 commit → 拒绝" "targetCommitish" "$TMP/out-target" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-target-other.json"

# ---- 资产形态 / version.json 绑定 ----
jq '.assets = [.assets[] | select(.name != "version.json")]' "$RELEASE_GOOD" > "$TMP/release-no-version-json.json"
run_case "缺 version.json → 拒绝" "version.json" "$TMP/out-no-version-json" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-no-version-json.json"

jq '.assets += [{name:"extra.txt",size:1}]' "$RELEASE_GOOD" > "$TMP/release-extra-asset.json"
run_case "多出非 Android 资产 → 拒绝（资产形态必须精确）" "资产不符合 Android 形态" "$TMP/out-extra" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-extra-asset.json"

jq '.assets = [.assets[] | select(.name != "pim-shell-android-v26.11.1042.apk")]' "$RELEASE_GOOD" \
  > "$TMP/release-no-shell.json"
run_case "缺 shell APK → 拒绝" "资产不符合 Android 形态" "$TMP/out-no-shell" "$REPO_GOOD" \
  FAKE_RELEASE_JSON="$TMP/release-no-shell.json"

mutate_assets() { # <name> <jq-filter>
  local dir="$TMP/assets-$1"
  rm -rf "$dir"
  cp -a "$ASSETS_GOOD" "$dir"
  jq "$2" "$dir/version.json" > "$dir/version.json.tmp"
  mv "$dir/version.json.tmp" "$dir/version.json"
  printf '%s\n' "$dir"
}

ASSETS_BAD_VERSION="$(mutate_assets bad-version '.version = "26.11.1041"')"
run_case "version.json 机器版本与 tag 不符 → 拒绝" "机器版本" "$TMP/out-vjson-version" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_VERSION"

ASSETS_BAD_VC="$(mutate_assets bad-vc '.versionCode = 101041')"
run_case "versionCode 与 tag 推导不符 → 拒绝" "versionCode" "$TMP/out-vjson-vc" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_VC"

ASSETS_BAD_DISPLAY="$(mutate_assets bad-display '.displayVersion = "1.0.0"')"
run_case "displayVersion 与目标不符 → 拒绝" "displayVersion" "$TMP/out-vjson-display" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_DISPLAY"

ASSETS_BAD_GITSHA="$(mutate_assets bad-gitsha '.gitSha = "2222222222222222222222222222222222222222"')"
run_case "gitSha 与 tag commit 不符 → 拒绝" "gitSha" "$TMP/out-vjson-sha" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_GITSHA"

ASSETS_BAD_TAG="$(mutate_assets bad-tag '.tag = "android-v26.11.1041"')"
run_case "version.json tag 不符 → 拒绝" "tag" "$TMP/out-vjson-tag" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_TAG"

ASSETS_BAD_RUNID="$(mutate_assets bad-runid '.runId = 1000')"
run_case "version.json runId 不符 → 拒绝" "runId" "$TMP/out-vjson-runid" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_RUNID"

ASSETS_BAD_RUNNUMBER="$(mutate_assets bad-runnumber '.runNumber = 41')"
run_case "version.json runNumber 不符 → 拒绝" "runNumber" "$TMP/out-vjson-runnumber" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_RUNNUMBER"

ASSETS_BAD_SHA="$(mutate_assets bad-sha '.assets.android.sha256 = "0000000000000000000000000000000000000000000000000000000000000000"')"
run_case "APK sha256 与清单不符 → 拒绝" "sha256" "$TMP/out-vjson-assetsha" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_SHA"

ASSETS_BAD_SIZE="$(mutate_assets bad-size '.assets.shellAndroid.size = 1')"
run_case "APK size 与清单不符 → 拒绝" "大小" "$TMP/out-vjson-assetsize" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_SIZE"

ASSETS_BAD_URL="$(mutate_assets bad-url '.assets.android.url = "https://example.com/pim-android-v26.11.1042.apk"')"
run_case "APK URL 未指向本 release → 拒绝" "url" "$TMP/out-vjson-url" "$REPO_GOOD" \
  FAKE_ASSET_SOURCE="$ASSETS_BAD_URL"

# ---- run 证明 ----
jq '.[0].number = 41' "$RUNS_GOOD" > "$TMP/runs-bad-number.json"
run_case "run number 与 tag（去掉 +1000）不符 → 拒绝" "run number" "$TMP/out-run-number" "$REPO_GOOD" \
  FAKE_RUNS_JSON="$TMP/runs-bad-number.json"

jq '.[0].headSha = "2222222222222222222222222222222222222222"' "$RUNS_GOOD" > "$TMP/runs-bad-sha.json"
run_case "run 列表 headSha 不是 tag commit → 拒绝" "没有成功的 master push" "$TMP/out-run-list-sha" "$REPO_GOOD" \
  FAKE_RUNS_JSON="$TMP/runs-bad-sha.json"

jq '.headSha = "2222222222222222222222222222222222222222"' "$RUN_DETAIL_GOOD" > "$TMP/run-detail-bad-sha.json"
run_case "run 详情 headSha 不一致 → 拒绝" "headSha" "$TMP/out-run-view-sha" "$REPO_GOOD" \
  FAKE_RUN_DETAIL="$TMP/run-detail-bad-sha.json"

jq '.headBranch = "develop"' "$RUN_DETAIL_GOOD" > "$TMP/run-detail-branch.json"
run_case "run 详情不是 master → 拒绝" "master 分支" "$TMP/out-run-view-branch" "$REPO_GOOD" \
  FAKE_RUN_DETAIL="$TMP/run-detail-branch.json"

jq '.conclusion = "failure"' "$RUN_DETAIL_GOOD" > "$TMP/run-detail-failure.json"
run_case "run 详情结论失败 → 拒绝" "不是成功构建" "$TMP/out-run-view-conclusion" "$REPO_GOOD" \
  FAKE_RUN_DETAIL="$TMP/run-detail-failure.json"

jq '.status = "in_progress" | .conclusion = null' "$RUN_DETAIL_GOOD" > "$TMP/run-detail-running.json"
run_case "run 详情未完成 → 拒绝" "还未完成" "$TMP/out-run-view-status" "$REPO_GOOD" \
  FAKE_RUN_DETAIL="$TMP/run-detail-running.json"

jq '.number = 41' "$RUN_DETAIL_GOOD" > "$TMP/run-detail-number.json"
run_case "run 详情 number 不一致 → 拒绝" "number" "$TMP/out-run-view-number" "$REPO_GOOD" \
  FAKE_RUN_DETAIL="$TMP/run-detail-number.json"

# ---- tag commit / VERSION ----
REPO_BAD_HEAD="$TMP/repo-bad-head"
make_repo "$REPO_BAD_HEAD" 2.0.0
SHA_BAD_HEAD="$(git -C "$REPO_BAD_HEAD" rev-parse HEAD)"
jq --arg sha "$SHA_BAD_HEAD" '.targetCommitish = $sha' "$RELEASE_GOOD" > "$TMP/release-bad-head.json"
run_case "当前 master VERSION 不是目标 → 拒绝" "当前 checkout 的 VERSION" "$TMP/out-head-version" "$REPO_BAD_HEAD" \
  FAKE_TAG_SHA="$SHA_BAD_HEAD" FAKE_RELEASE_JSON="$TMP/release-bad-head.json"

REPO_TWO_COMMITS="$TMP/repo-two-commits"
make_repo "$REPO_TWO_COMMITS" 2.0.0
SHA_OLD="$(git -C "$REPO_TWO_COMMITS" rev-parse HEAD)"
printf '1.1.0\n' > "$REPO_TWO_COMMITS/VERSION"
git -C "$REPO_TWO_COMMITS" add VERSION
git -C "$REPO_TWO_COMMITS" commit -q -m "bump"
SHA_HEAD="$(git -C "$REPO_TWO_COMMITS" rev-parse HEAD)"
jq --arg sha "$SHA_OLD" '.targetCommitish = $sha' "$RELEASE_GOOD" > "$TMP/release-old-version.json"
run_case "tag commit 的 VERSION 不是目标 → 拒绝" "tag commit 的 VERSION" "$TMP/out-tag-version" "$REPO_TWO_COMMITS" \
  FAKE_TAG_SHA="$SHA_OLD" FAKE_RELEASE_JSON="$TMP/release-old-version.json"

REPO_SIDE="$TMP/repo-side"
make_repo "$REPO_SIDE" 1.1.0
git -C "$REPO_SIDE" checkout -q -b side
printf '2.0.0\n' > "$REPO_SIDE/VERSION"
git -C "$REPO_SIDE" add VERSION
git -C "$REPO_SIDE" commit -q -m "side"
SHA_SIDE="$(git -C "$REPO_SIDE" rev-parse HEAD)"
git -C "$REPO_SIDE" checkout -q master
jq --arg sha "$SHA_SIDE" '.targetCommitish = $sha' "$RELEASE_GOOD" > "$TMP/release-side.json"
run_case "tag commit 不在当前 master 历史里 → 拒绝" "不在当前 master 历史" "$TMP/out-side" "$REPO_SIDE" \
  FAKE_TAG_SHA="$SHA_SIDE" FAKE_RELEASE_JSON="$TMP/release-side.json"

MISSING_SHA="3333333333333333333333333333333333333333"
jq --arg sha "$MISSING_SHA" '.targetCommitish = $sha' "$RELEASE_GOOD" > "$TMP/release-missing-commit.json"
run_case "tag commit 不在 checkout 里 → 拒绝" "不在当前 checkout" "$TMP/out-missing-commit" "$REPO_GOOD" \
  FAKE_TAG_SHA="$MISSING_SHA" FAKE_RELEASE_JSON="$TMP/release-missing-commit.json"

run_case "annotated tag 解析后仍绑定 commit → 通过" pass "$TMP/out-annotated" "$REPO_GOOD" \
  FAKE_TAG_TYPE=tag FAKE_ANNOTATED_SHA="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

if [[ "$fail" -ne 0 ]]; then
  echo "validate-promote-target tests failed: ${fail} failed, ${pass} passed" >&2
  exit 1
fi
echo "validate-promote-target tests passed (${pass} checks)"
