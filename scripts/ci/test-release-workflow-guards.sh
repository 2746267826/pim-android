#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 发布链路护栏的静态断言。
#
# 这些文件一旦漏接一个字段，行为上可能仍然全绿：CI 没传 --target、
# 旧 run 重跑把正式 tag 降回 pre-release、promote 用 GraphQL id 调 REST、
# 提升后不回读 latest……只有真发一次版才会暴露。这里把跨文件接线钉死。
#
# 所有 job 内断言都先捕获完整 block，再用 here-string 做 grep；避免
# `set -o pipefail` 下 grep -q 早退、awk 收到 SIGPIPE（141）造成假红/假绿。
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

pass=0
fail=0
check() { # <label> <file> <literal>
  local label="$1" file="$2" literal="$3"
  if grep -Fq -- "$literal" "$ROOT/$file"; then
    echo "  ✅ $label"
    pass=$((pass + 1))
  else
    echo "  ❌ $label — $file 缺少 [$literal]"
    fail=$((fail + 1))
  fi
}

check_absent() { # <label> <file> <literal>
  local label="$1" file="$2" literal="$3"
  if grep -Fq -- "$literal" "$ROOT/$file"; then
    echo "  ❌ $label — $file 仍包含 [$literal]"
    fail=$((fail + 1))
  else
    echo "  ✅ $label"
    pass=$((pass + 1))
  fi
}

job_block() { # <file> <job-name>
  awk -v job="$2" '
    $0 ~ "^  " job ":$" { inside=1; next }
    inside && /^  [A-Za-z0-9_-]+:$/ { exit }
    inside { print }
  ' "$ROOT/$1"
}

check_in_job() { # <label> <file> <job> <literal>
  local label="$1" file="$2" job="$3" literal="$4"
  local block
  block="$(job_block "$file" "$job")"
  if grep -Fq -- "$literal" <<<"$block"; then
    echo "  ✅ $label"
    pass=$((pass + 1))
  else
    echo "  ❌ $label — $file 的 job [$job] 缺少 [$literal]"
    fail=$((fail + 1))
  fi
}

check_not_in_job() { # <label> <file> <job> <literal>
  local label="$1" file="$2" job="$3" literal="$4"
  local block
  block="$(job_block "$file" "$job")"
  if grep -Fq -- "$literal" <<<"$block"; then
    echo "  ❌ $label — $file 的 job [$job] 仍包含 [$literal]"
    fail=$((fail + 1))
  else
    echo "  ✅ $label"
    pass=$((pass + 1))
  fi
}

check_sigpipe_regression() {
  local tmp rc contains_verdict absent_verdict
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/pim-release-guards-sigpipe.XXXXXX")"
  {
    printf 'jobs:\n  probe:\n    needle\n'
    awk 'BEGIN { for (i = 0; i < 8192; i++) printf "    padding-%04d-0123456789abcdef\n", i }'
    printf '  next:\n'
  } > "$tmp/workflow.yml"

  rc=0
  (
    ROOT="$tmp"
    set -o pipefail
    job_block workflow.yml probe | grep -Fq -- needle
  ) || rc=$?
  [[ "$rc" -eq 141 ]] || { rm -rf "$tmp"; return 1; }

  contains_verdict="$(
    ROOT="$tmp"
    pass=0
    fail=0
    check_in_job "sigpipe contains" workflow.yml probe needle >/dev/null 2>&1
    printf '%s/%s' "$pass" "$fail"
  )"
  absent_verdict="$(
    ROOT="$tmp"
    pass=0
    fail=0
    check_not_in_job "sigpipe absent" workflow.yml probe needle >/dev/null 2>&1
    printf '%s/%s' "$pass" "$fail"
  )"
  rm -rf "$tmp"
  [[ "$contains_verdict" == "1/0" && "$absent_verdict" == "0/1" ]]
}

echo "== release workflow guards =="

# ---- CI release job: target / guard / shared lock / read-back ----
check_in_job "CI release 持有共享发布锁" .github/workflows/ci.yml release 'group: pim-release-mutation'
check_in_job "CI release 调用发布守卫" .github/workflows/ci.yml release 'release-publish-guard.sh'
check_in_job "CI 发布守传入 run attempt" .github/workflows/ci.yml release 'RUN_ATTEMPT: ${{ github.run_attempt }}'
check_in_job "CI 发布守传入 commit sha" .github/workflows/ci.yml release 'SHA: ${{ github.sha }}'
check_in_job "CI 发布守传入 ref" .github/workflows/ci.yml release 'REF: ${{ github.ref }}'
check_in_job "CI 发布守传入 event" .github/workflows/ci.yml release 'EVENT: ${{ github.event_name }}'
check_in_job "CI release 用 --target 绑定本次 commit" .github/workflows/ci.yml release '--target "$SHA"'
check_in_job "CI release create 不省略 target" .github/workflows/ci.yml release 'gh release create "$TAG" out/*'
check_in_job "CI 发布 step 受 publish 守卫控制" .github/workflows/ci.yml release "steps.existing.outputs.publish == 'true'"
check_in_job "CI 跳过发布有可见 summary" .github/workflows/ci.yml release 'Report skipped release publication'
check_in_job "CI 生成资产用 prepare-release-assets.sh" .github/workflows/ci.yml release 'prepare-release-assets.sh'
check_in_job "CI 发布后回读 release/资产" .github/workflows/ci.yml release 'verify-published-release.sh'
check_in_job "CI 回读时对比本地资产" .github/workflows/ci.yml release '--assets-dir out'
check_in_job "CI 回读时要求 prerelease=true" .github/workflows/ci.yml release '--expect-prerelease true'

# ---- 发布守卫脚本 ----
check "发布守卫正式版不重发" scripts/ci/release-publish-guard.sh 'formal-exists'
check "发布守卫重跑只认当前 master HEAD" scripts/ci/release-publish-guard.sh 'commits/master'
check "发布守卫拒绝非 master" scripts/ci/release-publish-guard.sh '只允许从 master 发布'
check "发布守卫拒绝过期 run" scripts/ci/release-publish-guard.sh 'stale-rerun'
check "发布守卫按 +1000 补偿换算" scripts/ci/release-publish-guard.sh '历史补偿'
check "发布守卫 API 错误 fail-closed" scripts/ci/release-publish-guard.sh '不把 API 错误当成『不存在』'

# ---- release.yml: 入口 / bump PR / 合并 ----
check "release 只能从 master 触发" .github/workflows/release.yml 'refs/heads/master'
check "bump PR 技术修改章节" .github/workflows/release.yml '## 技术修改 / Technical changes'
check "bump PR 功能变化章节" .github/workflows/release.yml '## 功能变化 / Feature changes'
check "bump PR 如何体验章节" .github/workflows/release.yml '## 如何体验 / How to try it'
check "bump PR 测试章节" .github/workflows/release.yml '## 测试 / Tests'
check "bump PR 需求验收映射" .github/workflows/release.yml '## 需求与验收映射 / Requirement & Acceptance Mapping'
check "bump PR 未运行项标 NOT-VERIFIED" .github/workflows/release.yml 'NOT-VERIFIED'
check "已有 bump PR 也重写正文" .github/workflows/release.yml 'gh pr edit "$PR_NUM" --body-file'
check "bump PR 合并走行为脚本" .github/workflows/release.yml 'scripts/ci/merge-release-pr.sh'
check_absent "bump PR 不再吞掉 merge 错误" .github/workflows/release.yml 'gh pr merge "$PR_NUM" --squash --auto --match-head-commit "$HEAD_SHA" || true'
check "合并脚本拒绝 autoMergeRequest 为空" scripts/ci/merge-release-pr.sh 'autoMergeRequest 为空'
check "合并脚本只在 MERGED 后成功" scripts/ci/merge-release-pr.sh '当前 state='
check "合并脚本回读 merge commit VERSION" scripts/ci/merge-release-pr.sh 'contents/VERSION'

# ---- release.yml: 等待构建 / 定位 release ----
check "等待构建校验 headBranch=master" .github/workflows/release.yml '.headBranch == "master"'
check "等待构建校验 headSha=merge commit" .github/workflows/release.yml '.headSha == $sha'
check "等待构建校验 event=push" .github/workflows/release.yml '.event == "push"'
check "失败提示的 promote_tag 按 +1000 补偿" .github/workflows/release.yml '10#${RUN_NUMBER} + 1000'
check "定位 release 绑定 targetCommitish" .github/workflows/release.yml 'select(.target_commitish == $sha'
check "定位 release 校验机器序号" .github/workflows/release.yml 'EXPECTED_N=$(( 10#${RUN_NUMBER} + 1000 ))'

# ---- release.yml: prepare / apply / promote ----
check "prepare 作业不持有发布锁" .github/workflows/release.yml 'prepare:'
check_not_in_job "prepare 作业不占用 pim-release-mutation" .github/workflows/release.yml prepare 'group: pim-release-mutation'
check_in_job "apply 作业持有共享发布锁" .github/workflows/release.yml apply 'group: pim-release-mutation'
check_in_job "apply 作业同步当前 master" .github/workflows/release.yml apply 'git fetch --prune origin master'
check_in_job "apply 作业在锁内校验来源" .github/workflows/release.yml apply 'Validate promote target'
check "promote_tag 走完整来源校验脚本" .github/workflows/release.yml 'scripts/ci/validate-promote-target.sh'
check "提升使用 REST databaseId" .github/workflows/release.yml '--json databaseId'
check_in_job "apply 使用来源校验输出的 release_id" .github/workflows/release.yml apply 'RELEASE_ID: ${{ steps.target.outputs.release_id }}'
check "提升校验 databaseId 为纯数字" .github/workflows/release.yml '"$RELEASE_ID" =~ ^[0-9]+$'
check_absent "提升不把 databaseId 覆写成 GraphQL id" .github/workflows/release.yml '--json id --jq .id'
check "提升显式 make_latest" .github/workflows/release.yml 'make_latest=true'
check "提升后回读 release/资产/latest" .github/workflows/release.yml 'verify-published-release.sh'
check "提升后要求正式版" .github/workflows/release.yml '--expect-prerelease false'
check "提升后要求 latest 指向本 tag" .github/workflows/release.yml '--expect-latest true'
check "提升可重入（正式版不早退）" .github/workflows/release.yml '本次重入只收敛正文与 latest'
check_absent "提升不再幂等早退" .github/workflows/release.yml '已经是正式版；仍确认 make_latest'

# ---- 来源校验脚本 ----
check "来源校验从 REST 读 latest" scripts/ci/validate-promote-target.sh 'releases/latest'
check "来源校验从 REST databaseId 读 release id" scripts/ci/validate-promote-target.sh '--json databaseId,tagName,isPrerelease,isDraft,targetCommitish,assets'
check_absent "来源校验不请求不存在的 isLatest" scripts/ci/validate-promote-target.sh 'databaseId,tagName,isPrerelease,isDraft,targetCommitish,assets,isLatest'
check "来源校验绑定 targetCommitish" scripts/ci/validate-promote-target.sh 'TARGET_COMMITISH'
check "来源校验绑定 run headSha" scripts/ci/validate-promote-target.sh 'headSha'
check "来源校验绑定 version.json displayVersion" scripts/ci/validate-promote-target.sh 'displayVersion'
check "来源校验绑定 version.json gitSha" scripts/ci/validate-promote-target.sh 'gitSha'
check "来源校验绑定 APK sha256" scripts/ci/validate-promote-target.sh 'sha256'
check "来源校验绑定当前 manifest VERSION" scripts/ci/validate-promote-target.sh 'HEAD_VERSION'
check "来源校验拒绝 latest 回退" scripts/ci/validate-promote-target.sh 'latest 回退'
check "来源校验拒绝非 master 构建" scripts/ci/validate-promote-target.sh 'master push 构建'

# ---- 发布后回读脚本 ----
check "回读脚本使用 REST release id" scripts/ci/verify-published-release.sh 'REST 数字 id'
check "回读脚本校验 target_commitish" scripts/ci/verify-published-release.sh 'target_commitish'
check "回读脚本校验资产形态" scripts/ci/verify-published-release.sh '资产不符合 Android 形态'
check "回读脚本重新下载并校验 sha256" scripts/ci/verify-published-release.sh 'verify-version-json.sh'
check "回读脚本校验 /releases/latest" scripts/ci/verify-published-release.sh 'releases/latest'

# ---- 测试必须接入 CI ----
check "CI 跑发布 workflow 静态测试" .github/workflows/ci.yml 'test-release-workflow-guards.sh'
check "CI 跑发布守卫行为测试" .github/workflows/ci.yml 'test-release-publish-guard.sh'
check "CI 跑 bump PR 合并行为测试" .github/workflows/ci.yml 'test-merge-release-pr.sh'
check "CI 跑来源校验行为测试" .github/workflows/ci.yml 'test-validate-promote-target.sh'
check "CI 跑资产准备/回读行为测试" .github/workflows/ci.yml 'test-release-assets.sh'

# ---- 文档 ----
check "release 文档说明旧 run 不补发" docs/release-channels.md '过期 run 不补发'
check "release 文档说明 latest 回退防护" docs/release-channels.md '不会回退'
check "release 文档说明 auto-merge 未启用会失败" docs/release-channels.md '仓库需允许 auto-merge'
check "release 文档标注真机安装未验证" docs/release-channels.md '真机安装/升级'

# ---- YAML 解析 ----
if python3 -c 'import yaml' >/dev/null 2>&1; then
  if python3 - "$ROOT" <<'PY'
import sys, yaml
root = sys.argv[1]
for name in ("ci.yml", "release.yml"):
    with open(f"{root}/.github/workflows/{name}", encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    assert isinstance(doc, dict) and "jobs" in doc, name
    assert isinstance(doc["jobs"], dict) and doc["jobs"], name
print("ok")
PY
  then
    echo "  ✅ ci.yml / release.yml 通过 YAML 解析"
    pass=$((pass + 1))
  else
    echo "  ❌ ci.yml / release.yml YAML 解析失败"
    fail=$((fail + 1))
  fi
else
  echo "  ⏭  PyYAML 未安装，跳过 YAML 解析（CI 会安装并执行）"
fi

regression_fail=0
if check_sigpipe_regression; then
  echo "  ✅ SIGPIPE 回归：大 block 的命中/缺失判定稳定"
  sigpipe_status=PASS
else
  echo "  ❌ SIGPIPE 回归：job block 断言仍受 grep 早退/SIGPIPE 影响"
  regression_fail=1
  sigpipe_status=FAIL
fi

if [[ "$fail" -ne 0 || "$regression_fail" -ne 0 ]]; then
  echo "release workflow guard tests failed: ${fail} failed, ${pass} passed, SIGPIPE regression ${sigpipe_status}" >&2
  exit 1
fi
echo "release workflow guard tests passed (${pass} checks; SIGPIPE regression PASS)"
