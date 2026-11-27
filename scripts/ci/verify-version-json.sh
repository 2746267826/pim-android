#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 校验 Android release 的 version.json 是否绑定到指定的 tag / commit / run。
#
# Android 资产形态：
#   pim-android-v<machine>.apk
#   pim-shell-android-v<machine>.apk
#   version.json
#
# version.json 是更新检查的入口。只检查「文件存在」不够：必须证明里面的机器
# 版本、versionCode、显示版本、tag、commit、run 与待提升的构建一一对应，并且
# APK 的名称 / 大小 / sha256 与清单一致。带 --assets-dir 时还会校验本地文件
# 本身，防止清单写的是 A、上传的是 B。
#
# 用法：
#   scripts/ci/verify-version-json.sh \
#     --file version.json --tag android-v26.11.1042 \
#     --sha <40-hex> --version 26.11.1042 --tag-machine-version 26.11.1042 \
#     --version-code 101042 --display-version 1.0.0 \
#     --run-id 123456 --run-number 42 [--assets-dir out]
# ---------------------------------------------------------------------------
set -euo pipefail

FILE=""
TAG=""
SHA=""
VERSION=""
TAG_MACHINE_VERSION=""
VERSION_CODE=""
DISPLAY_VERSION=""
RUN_ID=""
RUN_NUMBER=""
ASSETS_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --file) FILE="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --sha) SHA="${2:-}"; shift 2 ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    --tag-machine-version) TAG_MACHINE_VERSION="${2:-}"; shift 2 ;;
    --version-code) VERSION_CODE="${2:-}"; shift 2 ;;
    --display-version) DISPLAY_VERSION="${2:-}"; shift 2 ;;
    --run-id) RUN_ID="${2:-}"; shift 2 ;;
    --run-number) RUN_NUMBER="${2:-}"; shift 2 ;;
    --assets-dir) ASSETS_DIR="${2:-}"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

die() {
  echo "::error::$*" >&2
  echo "$*" >&2
  exit 1
}

[[ -n "$FILE" && -f "$FILE" ]] || die "--file 不存在：'${FILE}'"
[[ -n "$TAG" ]] || die "--tag is required"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha 必须是 40 位 commit sha（收到 '${SHA}'）"
[[ "$VERSION" =~ ^[0-9]{2}\.[0-9]{2}\.[0-9]+(\+[0-9A-Za-z._-]+)?$ ]] \
  || die "--version 必须形如 26.11.1042 或 26.11.1042+android.1（收到 '${VERSION}'）"
[[ "$TAG_MACHINE_VERSION" =~ ^[0-9]{2}\.[0-9]{2}\.[0-9]+$ ]] \
  || die "--tag-machine-version 非法（收到 '${TAG_MACHINE_VERSION}'）"
[[ "${VERSION%%+*}" == "$TAG_MACHINE_VERSION" ]] \
  || die "version 核心 '${VERSION%%+*}' 与 tag 机器版本 '${TAG_MACHINE_VERSION}' 不一致"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "--version-code 必须是数字（收到 '${VERSION_CODE}'）"
[[ "$DISPLAY_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || die "--display-version 必须是 x.y.z（收到 '${DISPLAY_VERSION}'）"
[[ "$RUN_ID" =~ ^[0-9]+$ ]] || die "--run-id 必须是数字（收到 '${RUN_ID}'）"
[[ "$RUN_NUMBER" =~ ^[0-9]+$ ]] || die "--run-number 必须是数字（收到 '${RUN_NUMBER}'）"

command -v jq >/dev/null 2>&1 || die "jq is required"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required"

json_str() { # <jq filter> <label>
  local value
  value="$(jq -r "$1 // empty" "$FILE")"
  [[ -n "$value" ]] || die "version.json 缺少 ${2}"
  printf '%s\n' "$value"
}

json_num() { # <jq filter> <label>
  local value
  value="$(jq -r "$1 // empty" "$FILE")"
  [[ "$value" =~ ^[0-9]+$ ]] || die "version.json 的 ${2} 不是非负整数（收到 '${value}'）"
  printf '%s\n' "$value"
}

VERSION_FIELD="$(json_str '.version' '.version')"
[[ "$VERSION_FIELD" == "$VERSION" ]] \
  || die "version.json 的 version 是 '${VERSION_FIELD}'，期望 '${VERSION}'"

VERSION_CODE_FIELD="$(json_num '.versionCode' 'versionCode')"
[[ "$VERSION_CODE_FIELD" == "$VERSION_CODE" ]] \
  || die "version.json 的 versionCode 是 ${VERSION_CODE_FIELD}，期望 ${VERSION_CODE}"

DISPLAY_FIELD="$(json_str '.displayVersion' '.displayVersion')"
[[ "$DISPLAY_FIELD" == "$DISPLAY_VERSION" ]] \
  || die "version.json 的 displayVersion 是 '${DISPLAY_FIELD}'，期望 '${DISPLAY_VERSION}'（资产不是这次 VERSION 的构建）"

GIT_SHA_FIELD="$(json_str '.gitSha' '.gitSha')"
[[ "$GIT_SHA_FIELD" == "$SHA" ]] \
  || die "version.json 的 gitSha 是 '${GIT_SHA_FIELD}'，期望 '${SHA}'"

TAG_FIELD="$(json_str '.tag' '.tag')"
[[ "$TAG_FIELD" == "$TAG" ]] \
  || die "version.json 的 tag 是 '${TAG_FIELD}'，期望 '${TAG}'"

RUN_ID_FIELD="$(json_num '.runId' 'runId')"
[[ "$RUN_ID_FIELD" == "$RUN_ID" ]] \
  || die "version.json 的 runId 是 ${RUN_ID_FIELD}，期望 ${RUN_ID}"

RUN_NUMBER_FIELD="$(json_num '.runNumber' 'runNumber')"
[[ "$RUN_NUMBER_FIELD" == "$RUN_NUMBER" ]] \
  || die "version.json 的 runNumber 是 ${RUN_NUMBER_FIELD}，期望 ${RUN_NUMBER}"

verify_asset() { # <json-path> <expected-name>
  local path="$1" name="$2"
  local version url sha256 size file actual_sha actual_size
  version="$(json_str ".${path}.version" "${path}.version")"
  url="$(json_str ".${path}.url" "${path}.url")"
  sha256="$(json_str ".${path}.sha256" "${path}.sha256")"
  size="$(json_num ".${path}.size" "${path}.size")"

  [[ "$version" == "$VERSION" ]] \
    || die "version.json 的 ${path}.version 是 '${version}'，期望 '${VERSION}'"
  [[ "$sha256" =~ ^[0-9a-f]{64}$ ]] \
    || die "version.json 的 ${path}.sha256 不是 64 位小写十六进制（收到 '${sha256}'）"
  (( 10#$size > 0 )) || die "version.json 的 ${path}.size 必须大于 0"
  [[ "$url" == *"/releases/download/${TAG}/${name}" ]] \
    || die "version.json 的 ${path}.url 未指向 ${TAG}/${name}（收到 '${url}'）"

  if [[ -n "$ASSETS_DIR" ]]; then
    file="${ASSETS_DIR}/${name}"
    [[ -f "$file" ]] || die "资产目录缺少 ${name}"
    actual_size="$(wc -c < "$file" | tr -d ' ')"
    [[ "$actual_size" == "$size" ]] \
      || die "${name} 的大小是 ${actual_size}，version.json 记录 ${size}"
    actual_sha="$(sha256sum "$file" | awk '{print $1}')"
    [[ "$actual_sha" == "$sha256" ]] \
      || die "${name} 的 sha256 是 ${actual_sha}，version.json 记录 ${sha256}"
  fi
}

ANDROID_ASSET="pim-android-v${VERSION}.apk"
SHELL_ASSET="pim-shell-android-v${VERSION}.apk"
verify_asset "assets.android" "$ANDROID_ASSET"
verify_asset "assets.shellAndroid" "$SHELL_ASSET"

echo "version.json OK: tag=${TAG} sha=${SHA} version=${VERSION} code=${VERSION_CODE} display=${DISPLAY_VERSION} run=${RUN_NUMBER}/${RUN_ID}"
