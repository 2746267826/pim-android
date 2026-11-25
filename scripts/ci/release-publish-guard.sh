#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 决定一次 CI run 是否可以创建/刷新 tag 对应的 Android release。
#
# Android 的 tag/资产形态与 pim-api 不同，但发布安全语义相同：
#   * 只允许 master 的 push（或基于 master 当前 HEAD 的手动 dispatch）；
#   * release 已存在且是正式版 → publish=false（绝不碰它）；
#   * release 已存在但 targetCommitish 不是本次 commit → 失败；
#   * release 已存在但 tag 里的机器序号与本 run 对不上 → 失败；
#   * release 是 pre-release → 首次尝试允许刷新；重跑只有在该 commit 仍是
#     当前 master HEAD 时才允许，否则 publish=false；
#   * release 不存在 → 首次尝试允许创建；重跑/手动 dispatch 只有在该 commit
#     仍是当前 master HEAD 时才允许，否则 publish=false（不给历史 run 补发）；
#   * 任何 API 错误 → 退出非零，不把「读不到」当成「不存在」。
#
# Android 的机器序号有历史补偿：tag 里的 N = GITHUB_RUN_NUMBER + 1000。
# 这个偏移必须和 scripts/ci/resolve-version.sh 的 LEGACY_RUN_NUMBER_BASE 一致，
# 否则所有发布都会被误判成「tag run 号不匹配」。
#
# 该脚本在 concurrency group pim-release-mutation 内运行；release.yml 的
# apply 作业使用同一 group，因此「检查后发布」不会与正式版提升并发。
#
# 用法：
#   scripts/ci/release-publish-guard.sh \
#     --repo owner/repo --tag android-v26.11.1042 \
#     --sha <40-hex> --run-number 42 --run-attempt 1 \
#     --ref refs/heads/master --event push
#
# 输出：publish=true|false、reason=<...>、exists=true|false；
# 有 GITHUB_OUTPUT 时同时写入。
# ---------------------------------------------------------------------------
set -euo pipefail

REPO=""
TAG=""
SHA=""
RUN_NUMBER=""
RUN_ATTEMPT=""
REF=""
EVENT=""
PREFIX_OVERRIDE=""
BASE_OVERRIDE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --sha) SHA="${2:-}"; shift 2 ;;
    --run-number) RUN_NUMBER="${2:-}"; shift 2 ;;
    --run-attempt) RUN_ATTEMPT="${2:-}"; shift 2 ;;
    --ref) REF="${2:-}"; shift 2 ;;
    --event) EVENT="${2:-}"; shift 2 ;;
    --prefix) PREFIX_OVERRIDE="${2:-}"; shift 2 ;;
    --legacy-run-number-base) BASE_OVERRIDE="${2:-}"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

die() {
  echo "::error::$*" >&2
  echo "$*" >&2
  exit 1
}

[[ -n "$REPO" ]] || die "--repo is required"
[[ -n "$TAG" ]] || die "--tag is required"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha 必须是 40 位 commit sha（收到 '${SHA}'）"
[[ "$RUN_NUMBER" =~ ^[0-9]+$ ]] || die "--run-number 必须是数字（收到 '${RUN_NUMBER}'）"
[[ "$RUN_ATTEMPT" =~ ^[0-9]+$ ]] && (( 10#$RUN_ATTEMPT >= 1 )) \
  || die "--run-attempt 必须是 >=1 的整数（收到 '${RUN_ATTEMPT}'）"
[[ "$REF" == "refs/heads/master" ]] \
  || die "只允许从 master 发布（当前 ref='${REF:-<empty>}'）；非 master 的 run 不得创建或改写 release"
[[ "$EVENT" == "push" || "$EVENT" == "workflow_dispatch" ]] \
  || die "不支持的发布事件 '${EVENT}'（只允许 push / workflow_dispatch）"

command -v gh >/dev/null 2>&1 || die "gh is required"
command -v jq >/dev/null 2>&1 || die "jq is required"

REPO_NAME="${REPO##*/}"
PREFIX="${PREFIX_OVERRIDE:-}"
if [[ -z "$PREFIX" ]]; then
  PREFIX="${REPO_NAME#pim-}"
  if [[ -z "$PREFIX" || "$PREFIX" == "$REPO_NAME" ]]; then
    PREFIX="${PIM_REPO_PREFIX:-pim}"
  fi
fi
LEGACY_BASE="${BASE_OVERRIDE:-${PIM_LEGACY_RUN_NUMBER_BASE:-1000}}"
[[ "$LEGACY_BASE" =~ ^[0-9]+$ ]] || die "legacy run-number base 必须是数字（收到 '${LEGACY_BASE}'）"

TAG_BASE="${TAG%%+*}"
if [[ ! "$TAG_BASE" =~ ^${PREFIX}-v([0-9]{2})\.([0-9]{2})\.([0-9]+)$ ]]; then
  die "tag 不符合本仓格式 ${PREFIX}-vYY.MM.N：'${TAG}'"
fi
TAG_N="${BASH_REMATCH[3]}"
EXPECTED_RUN_NUMBER=$(( 10#$TAG_N - 10#$LEGACY_BASE ))
(( EXPECTED_RUN_NUMBER >= 1 )) \
  || die "tag ${TAG} 的机器序号 ${TAG_N} 小于历史补偿 ${LEGACY_BASE}，不可能来自本仓 CI"
(( 10#$EXPECTED_RUN_NUMBER == 10#$RUN_NUMBER )) \
  || die "tag 里的 run number ${EXPECTED_RUN_NUMBER} 与本次 run ${RUN_NUMBER} 不一致（已按 +${LEGACY_BASE} 补偿换算）"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-android-release-guard.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

emit_decision() {
  local publish="$1" reason="$2" exists="$3"
  printf 'publish=%s\nreason=%s\nexists=%s\n' "$publish" "$reason" "$exists"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'publish=%s\nreason=%s\nexists=%s\n' "$publish" "$reason" "$exists" >> "$GITHUB_OUTPUT"
  fi
  echo "release publish guard: publish=${publish} reason=${reason} exists=${exists}" >&2
}

master_head() {
  local file="$TMP/master.json" err="$TMP/master.err"
  if ! gh api "repos/${REPO}/commits/master" >"$file" 2>"$err"; then
    die "无法读取当前 master HEAD（fail-closed）：$(tr '\n' ' ' < "$err")"
  fi
  local sha
  sha="$(jq -r '.sha // empty' "$file")"
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] \
    || die "master HEAD 返回的 sha 非法（收到 '${sha}'，fail-closed）"
  printf '%s\n' "$sha"
}

freshness_decision() {
  local head="$1" reason_prefix="${2:-stale-rerun}" success_reason="${3:-rerun-current-head}"
  if [[ "$head" == "$SHA" ]]; then
    emit_decision true "$success_reason" "$RELEASE_EXISTS"
  else
    emit_decision false "$reason_prefix" "$RELEASE_EXISTS"
  fi
}

RELEASE_FILE="$TMP/release.json"
RELEASE_ERR="$TMP/release.err"
RELEASE_EXISTS=false
if gh api "repos/${REPO}/releases/tags/${TAG}" >"$RELEASE_FILE" 2>"$RELEASE_ERR"; then
  RELEASE_EXISTS=true
elif grep -qE '\(HTTP 404\)|"status"[[:space:]]*:[[:space:]]*404' \
    "$RELEASE_FILE" "$RELEASE_ERR" 2>/dev/null; then
  RELEASE_EXISTS=false
else
  die "无法读取 release ${TAG}（fail-closed，不把 API 错误当成『不存在』）：$(tr '\n' ' ' < "$RELEASE_ERR")"
fi

if [[ "$RELEASE_EXISTS" == "true" ]]; then
  RELEASE_TAG="$(jq -r '.tag_name // empty' "$RELEASE_FILE")"
  RELEASE_DRAFT="$(jq -r 'if has("draft") then .draft else "missing" end' "$RELEASE_FILE")"
  RELEASE_PRE="$(jq -r 'if has("prerelease") then .prerelease else "missing" end' "$RELEASE_FILE")"
  RELEASE_TARGET="$(jq -r '.target_commitish // empty' "$RELEASE_FILE")"

  [[ "$RELEASE_TAG" == "$TAG" ]] \
    || die "release 返回的 tag_name 是 '${RELEASE_TAG}'，不是 '${TAG}'"
  [[ "$RELEASE_DRAFT" == "false" ]] \
    || die "release ${TAG} 是 draft（收到 '${RELEASE_DRAFT}'）；拒绝由 CI 改写"
  [[ "$RELEASE_TARGET" =~ ^[0-9a-f]{40}$ ]] \
    || die "release ${TAG} 的 targetCommitish 不是 commit sha（收到 '${RELEASE_TARGET}'）；拒绝改写"
  [[ "$RELEASE_TARGET" == "$SHA" ]] \
    || die "release ${TAG} 的 targetCommitish 是 commit ${RELEASE_TARGET}，不是本次 ${SHA}；拒绝改写"
  [[ "$RELEASE_PRE" == "true" || "$RELEASE_PRE" == "false" ]] \
    || die "release ${TAG} 的 prerelease 不是布尔值（收到 '${RELEASE_PRE}'）"

  if [[ "$RELEASE_PRE" == "false" ]]; then
    emit_decision false "formal-exists" true
    exit 0
  fi

  if (( 10#$RUN_ATTEMPT > 1 )); then
    freshness_decision "$(master_head)"
    exit 0
  fi
  if [[ "$EVENT" != "push" ]]; then
    freshness_decision "$(master_head)" "stale-dispatch" "current-head-dispatch"
    exit 0
  fi
  emit_decision true "prerelease-refresh" true
  exit 0
fi

if (( 10#$RUN_ATTEMPT > 1 )); then
  freshness_decision "$(master_head)"
  exit 0
fi
if [[ "$EVENT" != "push" ]]; then
  freshness_decision "$(master_head)" "stale-dispatch" "current-head-dispatch"
  exit 0
fi
emit_decision true "missing" false
