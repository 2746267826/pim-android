#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 合并 release bump PR，并且只在 GitHub 明确回报 MERGED 后才算成功。
#
# 背景：`gh pr merge --auto` 在仓库未启用 auto-merge 时会失败。旧写法用
# `|| true` 把它吞掉，然后只轮询 state == MERGED；如果 PR 因为别的原因一直
# 开着，要等 90 分钟才超时；如果 PR 是上一次已经合并的旧对象，还会被当成
# 「本次合并成功」继续去提升一个并不属于本次输入的构建。
#
# 这里把规则收紧：
#   * PR 必须 base=master，head 必须是本次提交的 SHA；
#   * auto-merge 命令失败时，只有在 PR 已经是 MERGED 的情况下才继续；
#   * PR 仍为 OPEN 且 autoMergeRequest 为空 → 立即失败，明确提示 auto-merge
#     未启用，绝不把「没合并」当成成功；
#   * 合并后回读 mergeCommit，并校验该 commit 的 VERSION 等于目标版本；
#   * 任何 gh API 错误都 fail-closed。
#
# 用法：
#   scripts/ci/merge-release-pr.sh \
#     --repo owner/repo --pr 123 --sha <40-hex> --version 1.1.0
#
# 成功时输出 merge_sha=<sha>；有 GITHUB_OUTPUT 时同时写入。
# 测试可用 PIM_MERGE_TIMEOUT_SECONDS / PIM_MERGE_POLL_SECONDS /
# PIM_MERGE_AUTO_CONFIRM_SECONDS 缩短等待。
# ---------------------------------------------------------------------------
set -euo pipefail

REPO=""
PR=""
SHA=""
VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --pr) PR="${2:-}"; shift 2 ;;
    --sha) SHA="${2:-}"; shift 2 ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

die() {
  echo "::error::$*" >&2
  echo "$*" >&2
  exit 1
}

[[ -n "$REPO" ]] || die "--repo is required"
[[ "$PR" =~ ^[0-9]+$ ]] || die "--pr 必须是数字（收到 '${PR}'）"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha 必须是 40 位 commit sha（收到 '${SHA}'）"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "--version 必须是 x.y.z（收到 '${VERSION}'）"

command -v gh >/dev/null 2>&1 || die "gh is required"
command -v jq >/dev/null 2>&1 || die "jq is required"

TIMEOUT="${PIM_MERGE_TIMEOUT_SECONDS:-5400}"
POLL="${PIM_MERGE_POLL_SECONDS:-30}"
AUTO_CONFIRM="${PIM_MERGE_AUTO_CONFIRM_SECONDS:-20}"
for pair in "timeout=$TIMEOUT" "poll=$POLL" "auto-confirm=$AUTO_CONFIRM"; do
  value="${pair#*=}"
  [[ "$value" =~ ^[0-9]+$ ]] || die "${pair%%=*} 必须是数字（收到 '${value}'）"
done

emit_merge() {
  printf 'merge_sha=%s\n' "$1"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'merge_sha=%s\n' "$1" >> "$GITHUB_OUTPUT"
  fi
}

read_pr() {
  gh pr view "$PR" --repo "$REPO" \
    --json state,mergedAt,mergeCommit,baseRefName,headRefOid,autoMergeRequest
}

pr_field() { # <json> <jq-filter>
  jq -r "$2" <<<"$1"
}

verify_merged() { # <pr-json>
  local json="$1" state base head merged_at merge_sha content encoded decoded
  state="$(pr_field "$json" '.state // empty')"
  base="$(pr_field "$json" '.baseRefName // empty')"
  head="$(pr_field "$json" '.headRefOid // empty')"
  merged_at="$(pr_field "$json" '.mergedAt // empty')"
  merge_sha="$(pr_field "$json" '.mergeCommit.oid // empty')"

  [[ "$state" == "MERGED" ]] || die "PR #${PR} 当前 state='${state}'，不是 MERGED"
  [[ "$base" == "master" ]] || die "PR #${PR} 的 base 是 '${base}'，不是 master；拒绝把非 master 合并当成发版成功"
  [[ "$head" == "$SHA" ]] \
    || die "PR #${PR} 的 head 是 ${head:-<empty>}，不是本次提交 ${SHA}；拒绝沿用旧合并"
  [[ -n "$merged_at" && "$merged_at" != "null" ]] \
    || die "PR #${PR} 标记 MERGED 但没有 mergedAt；API 状态不完整（fail-closed）"
  [[ "$merge_sha" =~ ^[0-9a-f]{40}$ ]] \
    || die "PR #${PR} 的 mergeCommit.oid 非法（收到 '${merge_sha}'）"

  # 回读合并提交：commit 存在、位于 master、VERSION 等于目标显示版本。
  if ! gh api "repos/${REPO}/commits/${merge_sha}" >/dev/null 2>&1; then
    die "无法读取合并提交 ${merge_sha}（fail-closed）"
  fi
  if ! content="$(gh api "repos/${REPO}/contents/VERSION?ref=${merge_sha}" --jq '.content' 2>/dev/null)"; then
    die "无法读取合并提交 ${merge_sha} 的 VERSION（fail-closed）"
  fi
  encoded="$(tr -d '\r\n[:space:]' <<<"$content")"
  decoded="$(printf '%s' "$encoded" | base64 --decode 2>/dev/null | head -n 1 | tr -d '\r' | tr -d '[:space:]')"
  [[ "$decoded" == "$VERSION" ]] \
    || die "合并提交 ${merge_sha} 的 VERSION 是 '${decoded:-<empty>}'，不是目标 '${VERSION}'"

  emit_merge "$merge_sha"
  echo "PR #${PR} 已合并：${merge_sha}（VERSION=${VERSION}）" >&2
}

PR_JSON="$(read_pr)" || die "无法读取 PR #${PR}（fail-closed）"
STATE="$(pr_field "$PR_JSON" '.state // empty')"
BASE="$(pr_field "$PR_JSON" '.baseRefName // empty')"
[[ "$BASE" == "master" ]] || die "PR #${PR} 的 base 是 '${BASE}'，不是 master"

case "$STATE" in
  MERGED)
    verify_merged "$PR_JSON"
    exit 0
    ;;
  CLOSED)
    die "PR #${PR} 被关闭而没有合并，停止发版（版本号未变）"
    ;;
  OPEN) ;;
  *) die "PR #${PR} 的 state='${STATE}' 无法判断（fail-closed）" ;;
esac

MERGE_RC=0
MERGE_OUT=""
MERGE_OUT="$(gh pr merge "$PR" --repo "$REPO" --squash --auto \
  --match-head-commit "$SHA" 2>&1)" || MERGE_RC=$?

PR_JSON="$(read_pr)" || die "执行 merge 后无法读取 PR #${PR}（fail-closed）"
STATE="$(pr_field "$PR_JSON" '.state // empty')"
AUTO="$(pr_field "$PR_JSON" '.autoMergeRequest // empty')"
if [[ "$STATE" == "MERGED" ]]; then
  verify_merged "$PR_JSON"
  exit 0
fi
if [[ "$STATE" == "CLOSED" ]]; then
  die "PR #${PR} 被关闭而没有合并，停止发版（版本号未变）"
fi

if [[ -z "$AUTO" || "$AUTO" == "null" ]]; then
  if [[ "$MERGE_RC" -ne 0 ]]; then
    printf '%s\n' "$MERGE_OUT" >&2
    die "PR #${PR} 仍未合并，且无法启用 auto-merge。请在本仓启用 auto-merge，或手动合并 PR 后重跑；本步骤不会把未合并当成成功。"
  fi
  # gh 返回 0 但 GraphQL 读取可能短暂滞后。给一个有限确认窗口，仍为空就失败。
  deadline=$(( $(date +%s) + AUTO_CONFIRM ))
  while [[ -z "$AUTO" || "$AUTO" == "null" ]]; do
    if (( $(date +%s) >= deadline )); then
      die "PR #${PR} 的 autoMergeRequest 为空：auto-merge 未启用，或启用后未生效。拒绝把未合并当成成功。"
    fi
    sleep 2
    PR_JSON="$(read_pr)" || die "等待 auto-merge 时无法读取 PR #${PR}（fail-closed）"
    STATE="$(pr_field "$PR_JSON" '.state // empty')"
    AUTO="$(pr_field "$PR_JSON" '.autoMergeRequest // empty')"
    if [[ "$STATE" == "MERGED" ]]; then
      verify_merged "$PR_JSON"
      exit 0
    fi
    [[ "$STATE" == "CLOSED" ]] && die "PR #${PR} 被关闭而没有合并，停止发版"
  done
fi

DEADLINE=$(( $(date +%s) + TIMEOUT ))
while true; do
  PR_JSON="$(read_pr)" || die "等待 PR #${PR} 合并时无法读取状态（fail-closed）"
  STATE="$(pr_field "$PR_JSON" '.state // empty')"
  AUTO="$(pr_field "$PR_JSON" '.autoMergeRequest // empty')"
  BASE="$(pr_field "$PR_JSON" '.baseRefName // empty')"

  [[ "$BASE" == "master" ]] || die "PR #${PR} 在等待期间 base 变成 '${BASE}'，不是 master"
  if [[ "$STATE" == "MERGED" ]]; then
    verify_merged "$PR_JSON"
    exit 0
  fi
  if [[ "$STATE" == "CLOSED" ]]; then
    die "PR #${PR} 被关闭而没有合并，停止发版（版本号未变）"
  fi
  if [[ "$STATE" != "OPEN" ]]; then
    die "PR #${PR} 的 state='${STATE}' 无法判断（fail-closed）"
  fi
  if [[ -z "$AUTO" || "$AUTO" == "null" ]]; then
    die "PR #${PR} 仍在等待，但 autoMergeRequest 已为空（auto-merge 未启用或被关闭）。拒绝继续等待并误报成功。"
  fi
  if (( $(date +%s) >= DEADLINE )); then
    die "等待 PR #${PR} 自动合并超时（${TIMEOUT}s）。门禁未通过或 auto-merge 未生效；检查 https://github.com/${REPO}/pull/${PR}"
  fi
  sleep "$POLL"
done
