#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 归一化 Android 构建产物并生成可验证的 version.json。
#
# CI 的 build-android / build-shell-android 产出的是 artifact 目录里的
# `pim-android-v<slug>-vc<code>.apk`。release 资产必须固定为：
#   pim-android-v<machine>.apk
#   pim-shell-android-v<machine>.apk
#   version.json
#
# version.json 除了更新检查原有的 version / versionCode / assets 之外，还记录
# tag、gitSha、displayVersion、runId、runNumber 以及每个 APK 的 sha256/size。
# 这样正式版提升前可以证明「要提升的资产就是那次 master 构建的产物」，而不是
# 同名但内容不同的文件。
#
# 用法：
#   scripts/ci/prepare-release-assets.sh \
#     --repo owner/pim-android --tag android-v26.11.1042 \
#     --version 26.11.1042 --version-code 101042 --display-version 1.0.0 \
#     --sha <40-hex> --run-id 123456 --run-number 42 \
#     --download-dir dl --out-dir out
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REPO=""
TAG=""
VERSION=""
VERSION_CODE=""
DISPLAY_VERSION=""
SHA=""
RUN_ID=""
RUN_NUMBER=""
DOWNLOAD_DIR=""
OUT_DIR=""
PREFIX_OVERRIDE=""
BASE_OVERRIDE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    --version-code) VERSION_CODE="${2:-}"; shift 2 ;;
    --display-version) DISPLAY_VERSION="${2:-}"; shift 2 ;;
    --sha) SHA="${2:-}"; shift 2 ;;
    --run-id) RUN_ID="${2:-}"; shift 2 ;;
    --run-number) RUN_NUMBER="${2:-}"; shift 2 ;;
    --download-dir) DOWNLOAD_DIR="${2:-}"; shift 2 ;;
    --out-dir) OUT_DIR="${2:-}"; shift 2 ;;
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
[[ "$VERSION" =~ ^[0-9]{2}\.[0-9]{2}\.[0-9]+(\+[0-9A-Za-z._-]+)?$ ]] \
  || die "--version 必须形如 26.11.1042 或 26.11.1042+android.1（收到 '${VERSION}'）"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "--version-code 必须是数字（收到 '${VERSION_CODE}'）"
[[ "$DISPLAY_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || die "--display-version 必须是 x.y.z（收到 '${DISPLAY_VERSION}'）"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha 必须是 40 位 commit sha（收到 '${SHA}'）"
[[ "$RUN_ID" =~ ^[0-9]+$ ]] || die "--run-id 必须是数字（收到 '${RUN_ID}'）"
[[ "$RUN_NUMBER" =~ ^[0-9]+$ ]] || die "--run-number 必须是数字（收到 '${RUN_NUMBER}'）"
[[ -n "$DOWNLOAD_DIR" && -d "$DOWNLOAD_DIR" ]] || die "--download-dir 不存在：'${DOWNLOAD_DIR}'"
[[ -n "$OUT_DIR" ]] || die "--out-dir is required"

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
TAG_YY="${BASH_REMATCH[1]}"
TAG_MM="${BASH_REMATCH[2]}"
TAG_N="${BASH_REMATCH[3]}"
TAG_MACHINE_VERSION="$((10#$TAG_YY)).${TAG_MM}.$((10#$TAG_N))"
[[ "${VERSION%%+*}" == "$TAG_MACHINE_VERSION" ]] \
  || die "version 核心 '${VERSION%%+*}' 与 tag 机器版本 '${TAG_MACHINE_VERSION}' 不一致"
EXPECTED_VERSION_CODE=$(( 100000 + 10#$TAG_N ))
[[ "$VERSION_CODE" == "$EXPECTED_VERSION_CODE" ]] \
  || die "versionCode ${VERSION_CODE} 与 tag ${TAG} 推导的 ${EXPECTED_VERSION_CODE} 不一致"
EXPECTED_RUN_NUMBER=$(( 10#$TAG_N - 10#$LEGACY_BASE ))
(( EXPECTED_RUN_NUMBER >= 1 )) \
  || die "tag ${TAG} 的机器序号 ${TAG_N} 小于历史补偿 ${LEGACY_BASE}"
[[ "$RUN_NUMBER" == "$EXPECTED_RUN_NUMBER" ]] \
  || die "run number ${RUN_NUMBER} 与 tag ${TAG} 推导的 ${EXPECTED_RUN_NUMBER} 不一致（已按 +${LEGACY_BASE} 补偿换算）"

command -v jq >/dev/null 2>&1 || die "jq is required"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required"

shopt -s nullglob
ANDROID_APKS=( "$DOWNLOAD_DIR"/pim-android-*/*.apk )
SHELL_APKS=( "$DOWNLOAD_DIR"/pim-shell-android-*/*.apk )
shopt -u nullglob
(( ${#ANDROID_APKS[@]} == 1 )) \
  || die "build-android 产物必须恰好 1 个 APK（实际 ${#ANDROID_APKS[@]} 个）"
(( ${#SHELL_APKS[@]} == 1 )) \
  || die "build-shell-android 产物必须恰好 1 个 APK（实际 ${#SHELL_APKS[@]} 个）"

mkdir -p "$OUT_DIR"
ANDROID_NAME="pim-android-v${VERSION}.apk"
SHELL_NAME="pim-shell-android-v${VERSION}.apk"
cp "${ANDROID_APKS[0]}" "$OUT_DIR/$ANDROID_NAME"
cp "${SHELL_APKS[0]}" "$OUT_DIR/$SHELL_NAME"

verify_apk_magic() { # <file>
  local magic
  magic="$(od -An -tx1 -N2 "$1" | tr -d ' \n')"
  [[ "$magic" == "504b" ]] || die "$(basename "$1") 不是 ZIP/APK（魔数 ${magic}）"
  (( $(wc -c < "$1") > 0 )) || die "$(basename "$1") 是空文件"
}
verify_apk_magic "$OUT_DIR/$ANDROID_NAME"
verify_apk_magic "$OUT_DIR/$SHELL_NAME"

ANDROID_SHA="$(sha256sum "$OUT_DIR/$ANDROID_NAME" | awk '{print $1}')"
SHELL_SHA="$(sha256sum "$OUT_DIR/$SHELL_NAME" | awk '{print $1}')"
ANDROID_SIZE="$(wc -c < "$OUT_DIR/$ANDROID_NAME" | tr -d ' ')"
SHELL_SIZE="$(wc -c < "$OUT_DIR/$SHELL_NAME" | tr -d ' ')"
BASE_URL="https://github.com/${REPO}/releases/download/${TAG}"
RELEASED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --arg version "$VERSION" \
  --argjson versionCode "$VERSION_CODE" \
  --arg displayVersion "$DISPLAY_VERSION" \
  --arg tag "$TAG" \
  --arg gitSha "$SHA" \
  --argjson runId "$RUN_ID" \
  --argjson runNumber "$RUN_NUMBER" \
  --arg releasedAt "$RELEASED_AT" \
  --arg base "$BASE_URL" \
  --arg an "$ANDROID_NAME" --arg as "$ANDROID_SHA" --argjson asz "$ANDROID_SIZE" \
  --arg sn "$SHELL_NAME" --arg ss "$SHELL_SHA" --argjson ssz "$SHELL_SIZE" \
  '{
    version: $version,
    versionCode: $versionCode,
    displayVersion: $displayVersion,
    tag: $tag,
    gitSha: $gitSha,
    runId: $runId,
    runNumber: $runNumber,
    releasedAt: $releasedAt,
    assets: {
      android: {
        version: $version,
        url: ($base + "/" + $an),
        sha256: $as,
        size: $asz
      },
      shellAndroid: {
        version: $version,
        url: ($base + "/" + $sn),
        sha256: $ss,
        size: $ssz
      }
    }
  }' > "$OUT_DIR/version.json"

bash "$SCRIPT_DIR/verify-version-json.sh" \
  --file "$OUT_DIR/version.json" \
  --tag "$TAG" \
  --sha "$SHA" \
  --version "$VERSION" \
  --tag-machine-version "$TAG_MACHINE_VERSION" \
  --version-code "$VERSION_CODE" \
  --display-version "$DISPLAY_VERSION" \
  --run-id "$RUN_ID" \
  --run-number "$RUN_NUMBER" \
  --assets-dir "$OUT_DIR"

echo "prepared release assets in ${OUT_DIR}: ${ANDROID_NAME}, ${SHELL_NAME}, version.json"
