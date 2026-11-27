#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 发布/提升后回读远端 release，而不是把「gh 命令没报错」当成成功。
#
# 校验：
#   * release id 是 REST 数字 id（不是 GraphQL 节点 id），且与期望一致；
#   * tag_name / target_commitish / draft / prerelease 与目标状态一致；
#   * 资产恰好是 Android APK ×2 + version.json，大小非 0；
#   * 重新下载三个资产并用 version.json 校验 sha256/size；
#   * 给了 --assets-dir 时，再与本地待上传文件逐字节比较；
#   * 需要 latest 时，/releases/latest 必须指向同一个数字 id 和 tag。
#
# 用法：
#   scripts/ci/verify-published-release.sh \
#     --repo owner/pim-android --tag android-v26.11.1042 \
#     --sha <40-hex> --version 26.11.1042 --tag-machine-version 26.11.1042 \
#     --version-code 101042 --display-version 1.0.0 \
#     --run-id 123456 --run-number 42 \
#     --expect-prerelease true [--expect-latest false] \
#     [--release-id 500] [--assets-dir out]
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REPO=""
TAG=""
SHA=""
VERSION=""
TAG_MACHINE_VERSION=""
VERSION_CODE=""
DISPLAY_VERSION=""
RUN_ID=""
RUN_NUMBER=""
EXPECT_PRE=""
EXPECT_LATEST="false"
RELEASE_ID=""
ASSETS_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --sha) SHA="${2:-}"; shift 2 ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    --tag-machine-version) TAG_MACHINE_VERSION="${2:-}"; shift 2 ;;
    --version-code) VERSION_CODE="${2:-}"; shift 2 ;;
    --display-version) DISPLAY_VERSION="${2:-}"; shift 2 ;;
    --run-id) RUN_ID="${2:-}"; shift 2 ;;
    --run-number) RUN_NUMBER="${2:-}"; shift 2 ;;
    --expect-prerelease) EXPECT_PRE="${2:-}"; shift 2 ;;
    --expect-latest) EXPECT_LATEST="${2:-}"; shift 2 ;;
    --release-id) RELEASE_ID="${2:-}"; shift 2 ;;
    --assets-dir) ASSETS_DIR="${2:-}"; shift 2 ;;
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
[[ "$EXPECT_PRE" == "true" || "$EXPECT_PRE" == "false" ]] \
  || die "--expect-prerelease 必须是 true|false（收到 '${EXPECT_PRE}'）"
[[ "$EXPECT_LATEST" == "true" || "$EXPECT_LATEST" == "false" ]] \
  || die "--expect-latest 必须是 true|false（收到 '${EXPECT_LATEST}'）"
[[ -z "$RELEASE_ID" || "$RELEASE_ID" =~ ^[0-9]+$ ]] \
  || die "--release-id 必须是数字（收到 '${RELEASE_ID}'）"

command -v gh >/dev/null 2>&1 || die "gh is required"
command -v jq >/dev/null 2>&1 || die "jq is required"
command -v cmp >/dev/null 2>&1 || die "cmp is required"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/pim-android-published.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

RELEASE_JSON="$(gh api "repos/${REPO}/releases/tags/${TAG}")" \
  || die "无法读取 release ${TAG}（fail-closed）"
ID="$(jq -r '.id // empty' <<<"$RELEASE_JSON")"
TAG_NAME="$(jq -r '.tag_name // empty' <<<"$RELEASE_JSON")"
TARGET="$(jq -r '.target_commitish // empty' <<<"$RELEASE_JSON")"
DRAFT="$(jq -r 'if has("draft") then .draft else "missing" end' <<<"$RELEASE_JSON")"
PRE="$(jq -r 'if has("prerelease") then .prerelease else "missing" end' <<<"$RELEASE_JSON")"

[[ "$ID" =~ ^[0-9]+$ ]] || die "release id 不是 REST 数字 id（收到 '${ID}'）"
[[ -z "$RELEASE_ID" || "$ID" == "$RELEASE_ID" ]] \
  || die "release id 是 ${ID}，期望 ${RELEASE_ID}"
[[ "$TAG_NAME" == "$TAG" ]] || die "release tag_name 是 '${TAG_NAME}'，不是 '${TAG}'"
[[ "$TARGET" == "$SHA" ]] \
  || die "release target_commitish 是 '${TARGET}'，不是 '${SHA}'"
[[ "$DRAFT" == "false" ]] || die "release 仍是 draft（收到 '${DRAFT}'）"
[[ "$PRE" == "$EXPECT_PRE" ]] \
  || die "release prerelease 是 '${PRE}'，期望 '${EXPECT_PRE}'"

ANDROID_NAME="pim-android-v${VERSION}.apk"
SHELL_NAME="pim-shell-android-v${VERSION}.apk"
EXPECTED_ASSETS="$(printf '%s\n' "$ANDROID_NAME" "$SHELL_NAME" 'version.json' | sort)"
ACTUAL_ASSETS="$(jq -r '.assets[]?.name // empty' <<<"$RELEASE_JSON" | sort)"
[[ "$ACTUAL_ASSETS" == "$EXPECTED_ASSETS" ]] \
  || die "release 资产不符合 Android 形态，期望 [${EXPECTED_ASSETS//$'\n'/, }]，实际 [${ACTUAL_ASSETS//$'\n'/, }]"

for name in "$ANDROID_NAME" "$SHELL_NAME" 'version.json'; do
  size="$(jq -r --arg n "$name" '.assets[]? | select(.name == $n) | .size // empty' <<<"$RELEASE_JSON")"
  [[ "$size" =~ ^[0-9]+$ ]] && (( 10#$size > 0 )) \
    || die "release 资产 ${name} 大小非法（收到 '${size}'）"
done

download_asset() { # <name>
  if ! gh release download "$TAG" --repo "$REPO" --pattern "$1" --dir "$TMP" >/dev/null 2>"$TMP/dl.err"; then
    die "无法下载 release 资产 $1：$(tr '\n' ' ' < "$TMP/dl.err")"
  fi
  [[ -f "$TMP/$1" ]] || die "下载后找不到资产 $1"
}
download_asset "$ANDROID_NAME"
download_asset "$SHELL_NAME"
download_asset 'version.json'

if [[ -n "$ASSETS_DIR" ]]; then
  for name in "$ANDROID_NAME" "$SHELL_NAME" 'version.json'; do
    [[ -f "$ASSETS_DIR/$name" ]] || die "本地资产目录缺少 ${name}"
    cmp -s "$ASSETS_DIR/$name" "$TMP/$name" \
      || die "远端资产 ${name} 与本地待上传文件不一致"
  done
fi

bash "$SCRIPT_DIR/verify-version-json.sh" \
  --file "$TMP/version.json" \
  --tag "$TAG" \
  --sha "$SHA" \
  --version "$VERSION" \
  --tag-machine-version "$TAG_MACHINE_VERSION" \
  --version-code "$VERSION_CODE" \
  --display-version "$DISPLAY_VERSION" \
  --run-id "$RUN_ID" \
  --run-number "$RUN_NUMBER" \
  --assets-dir "$TMP"

if [[ "$EXPECT_LATEST" == "true" ]]; then
  CONFIRM_SECONDS="${PIM_LATEST_CONFIRM_SECONDS:-30}"
  [[ "$CONFIRM_SECONDS" =~ ^[0-9]+$ ]] \
    || die "PIM_LATEST_CONFIRM_SECONDS 必须是数字（收到 '${CONFIRM_SECONDS}'）"
  DEADLINE=$(( $(date +%s) + CONFIRM_SECONDS ))
  while true; do
    if ! LATEST_JSON="$(gh api "repos/${REPO}/releases/latest" 2>"$TMP/latest.err")"; then
      die "无法读取 /releases/latest（fail-closed）：$(tr '\n' ' ' < "$TMP/latest.err")"
    fi
    LATEST_ID="$(jq -r '.id // empty' <<<"$LATEST_JSON")"
    LATEST_TAG="$(jq -r '.tag_name // empty' <<<"$LATEST_JSON")"
    LATEST_PRE="$(jq -r 'if has("prerelease") then .prerelease else "missing" end' <<<"$LATEST_JSON")"
    LATEST_DRAFT="$(jq -r 'if has("draft") then .draft else "missing" end' <<<"$LATEST_JSON")"
    if [[ "$LATEST_ID" == "$ID" && "$LATEST_TAG" == "$TAG" && "$LATEST_PRE" == "false" && "$LATEST_DRAFT" == "false" ]]; then
      break
    fi
    if (( $(date +%s) >= DEADLINE )); then
      die "/releases/latest 未收敛：tag=${LATEST_TAG} id=${LATEST_ID} prerelease=${LATEST_PRE} draft=${LATEST_DRAFT}，期望 ${TAG} id=${ID}"
    fi
    sleep 2
  done
fi

echo "published release OK: tag=${TAG} id=${ID} sha=${SHA} prerelease=${PRE} latest=${EXPECT_LATEST}"
