#!/usr/bin/env bash
set -euo pipefail

# 仓根（脚本在 scripts/ci/ 下）：VERSION 文件按仓根定位，不依赖调用方的 cwd。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DATE_OVERRIDE=""
PRINT_ENV=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DATE_OVERRIDE="$2"; shift 2 ;;
    --print-env) PRINT_ENV=true; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ -n "$DATE_OVERRIDE" ]]; then
  YEAR="${DATE_OVERRIDE:0:4}"
  MONTH="${DATE_OVERRIDE:5:2}"
else
  YEAR="$(date -u +%Y)"
  MONTH="$(date -u +%m)"
fi

N="${GITHUB_RUN_NUMBER:-0}"
if ! [[ "$N" =~ ^[0-9]+$ ]] || [[ "$N" -lt 1 ]]; then
  echo "GITHUB_RUN_NUMBER must be positive integer, got: ${GITHUB_RUN_NUMBER-}" >&2
  exit 1
fi

# 历史基线补偿（一次性、永久生效）。拆仓前本客户端的序号来自 monorepo 的运行序号，
# 用户机器上已装到的最大值是 815（2026-10-10 的 v2026.10.815，versionCode 100815）。
# 拆仓后 GITHUB_RUN_NUMBER 从 1 重新计数，若直接使用，新版本号会小于已装的旧版本：
#   1) 更新检查（Android / Windows 的 UpdateChecker）只比较版本号**最后一段数字**，
#      旧客户端会认为自己是新版 → 永远不提示更新；
#   2) Android 的 versionCode 由序号推导，变小后系统按「降级」拒绝安装
#      （INSTALL_FAILED_VERSION_DOWNGRADE，签名一致也没用）。
# 故叠加补偿基数 1000（> 历史最大值 815，留出余量），此后随运行序号单调递增。
# 只在客户端仓（android / windows）加：api / web 没有安装层，序号本就连续。
LEGACY_RUN_NUMBER_BASE=1000
N=$((LEGACY_RUN_NUMBER_BASE + N))

# ---------------------------------------------------------------------------
# 显示版本（给人看的那个号），来自仓根 VERSION 文件。
#
# 机器号（version 的 CalVer 与 versionCode）仍是更新检查、安装降级判断的唯一依据，
# 显示号只出现在界面上。所以显示号可以长期停在 1.0.0，中间几十个 dev 构建也不会
# 出现「版本回退」；正式版才由 release 工作流改一次。
# 文件缺失或格式不对一律硬失败，避免等发版时才发现写错。
# ---------------------------------------------------------------------------
VERSION_FILE="${PIM_VERSION_FILE:-$ROOT/VERSION}"
DISPLAY_BASE=""
if [[ -f "$VERSION_FILE" ]]; then
  DISPLAY_BASE="$(head -n 1 "$VERSION_FILE" | tr -d '\r' | tr -d '[:space:]')"
fi
if [[ -z "$DISPLAY_BASE" ]]; then
  echo "VERSION file missing or empty: $VERSION_FILE (expected a single line like 1.0.0)" >&2
  exit 1
fi
if ! [[ "$DISPLAY_BASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid display version in $VERSION_FILE: '${DISPLAY_BASE}' (expected x.y.z)" >&2
  exit 1
fi

SHA_FULL="${GITHUB_SHA:-0000000000000000000000000000000000000000}"
SHA7="${SHA_FULL:0:7}"
# 对外显示的版本号用两位年份（26.10.42）；程序集版本（assembly_version）仍用四位
# 年份，因为 .NET 程序集版本不值得为了好看去动。
YEAR_MONTH="${YEAR:2}.${MONTH}"
BASE="${YEAR_MONTH}.${N}"
VERSION_CODE=$((100000 + N))

# Release tag 带仓库前缀：pim-android -> android-v26.10.42
# 前缀由仓库名推导（去掉 pim-），四个仓共用同一份脚本而无需各自传参。
# GITHUB_REPOSITORY 是 "owner/repo"，先取最后一段；再以 :- 兜底，因为脚本
# 在 set -u 下运行，本地直接跑（无该变量）时裸引用会以 unbound variable 崩掉。
REPO_NAME="${GITHUB_REPOSITORY:-}"
REPO_NAME="${REPO_NAME##*/}"
PREFIX="${REPO_NAME#pim-}"
if [[ -z "$PREFIX" || "$PREFIX" == "$REPO_NAME" ]]; then
  # 本地运行（无 GITHUB_REPOSITORY）或仓库名不带 pim- 前缀时的兜底
  PREFIX="${PIM_REPO_PREFIX:-pim}"
fi
RELEASE_TAG="${PREFIX}-v${BASE}"

REF="${GITHUB_REF:-}"
EVENT="${GITHUB_EVENT_NAME:-}"
CLIENT_PATCH="${CLIENT_PATCH:-}"

is_release=false
version="$BASE"
if [[ "$REF" == "refs/heads/master" ]]; then
  is_release=true
  if [[ -n "$CLIENT_PATCH" ]]; then
    version="${BASE}+${CLIENT_PATCH}"
  fi
  # master 上是干净的显示号：正式版被「提升」时，产物里带的必须就是它。
  display_version="$DISPLAY_BASE"
elif [[ "$EVENT" == "pull_request" ]]; then
  PR_NUMBER="${PR_NUMBER:-${GITHUB_PR_NUMBER:-}}"
  if [[ -z "$PR_NUMBER" && "$REF" =~ refs/pull/([0-9]+)/ ]]; then
    PR_NUMBER="${BASH_REMATCH[1]}"
  fi
  if [[ -z "$PR_NUMBER" ]]; then
    echo "PR_NUMBER required for pull_request" >&2
    exit 1
  fi
  version="${BASE}-pr.${PR_NUMBER}+${SHA7}"
  display_version="${DISPLAY_BASE}-pr.${PR_NUMBER}.${SHA7}"
else
  version="${BASE}-dev+${SHA7}"
  display_version="${DISPLAY_BASE}-dev+${SHA7}"
fi

# filesystem-safe slug
artifact_slug="$(echo "$version" | sed 's/+/-/g')"

assembly_version="${YEAR}.$((10#$MONTH)).${N}.0"

if [[ "$PRINT_ENV" == true ]]; then
  cat <<EOF
version=$version
display_version=$display_version
version_code=$VERSION_CODE
artifact_slug=$artifact_slug
git_sha_short=$SHA7
is_release=$is_release
year_month=$YEAR_MONTH
assembly_version=$assembly_version
base_version=$BASE
release_tag=$RELEASE_TAG
EOF
  exit 0
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version=$version"
    echo "display_version=$display_version"
    echo "version_code=$VERSION_CODE"
    echo "artifact_slug=$artifact_slug"
    echo "git_sha_short=$SHA7"
    echo "is_release=$is_release"
    echo "year_month=$YEAR_MONTH"
    echo "assembly_version=$assembly_version"
    echo "base_version=$BASE"
    echo "release_tag=$RELEASE_TAG"
  } >> "$GITHUB_OUTPUT"
fi

echo "Resolved version=$version code=$VERSION_CODE tag=$RELEASE_TAG release=$is_release"
