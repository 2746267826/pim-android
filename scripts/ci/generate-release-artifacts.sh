#!/usr/bin/env bash
#
# 发布纯逻辑入口（F-A-01 / REQ-COM-04 前半 / I-3）
#
# 把两个构建产物规范成正式资产名，并生成客户端更新检查用的 `version.json`。
# 这是**唯一**一份发布资产/清单生成逻辑：
#   - CI 的 `release-logic` 作业在 PR / push master 上无条件运行（`contents: read`、无 secrets），
#     它失败会让 required `CI Gate` 变红 —— 发布纯逻辑因此在合并前就有执行路径；
#   - 真正发版的 `release` 作业调用**同一个**入口，避免两份脚本漂移。
#
# 用法：
#   bash scripts/ci/generate-release-artifacts.sh <version> <version_code> <release_tag> <owner/repo>
#
# 输入（相对当前工作目录，即 actions/download-artifact 的落盘布局）：
#   dl/pim-android-*/*.apk        安卓客户端产物
#   dl/pim-shell-android-*/*.apk  安卓壳产物
#
# 输出：
#   out/pim-android-v<version>.apk
#   out/pim-shell-android-v<version>.apk
#   out/version.json
#
# 失败即非 0（不允许假成功）：缺少任一产物、version_code 非数字、jq 生成失败。
# 本脚本不调用 gh，也不需要 `contents: write`，因此可以在 PR 上安全执行。
# 生成失败的残留文件不在这里清理，也不作要求：入口只承诺「非 0 且不发版」。
set -euo pipefail

USAGE="用法：bash scripts/ci/generate-release-artifacts.sh <version> <version_code> <release_tag> <owner/repo>"

fail() {
  echo "::error::$*" >&2
  exit 1
}

VERSION="${1:-}"
VERSION_CODE="${2:-}"
RELEASE_TAG="${3:-}"
REPO="${4:-}"

[ -n "$VERSION" ] || fail "缺少 version 参数。$USAGE"
[ -n "$VERSION_CODE" ] || fail "缺少 version_code 参数。$USAGE"
[ -n "$RELEASE_TAG" ] || fail "缺少 release_tag 参数。$USAGE"
[ -n "$REPO" ] || fail "缺少 owner/repo 参数。$USAGE"

# versionCode 在客户端是数字字段：非数字必须在这里失败，而不是生成一份坏清单。
case "$VERSION_CODE" in
  '' | *[!0-9]*) fail "version_code 必须是纯数字，实际：'$VERSION_CODE'" ;;
esac

# 取第一个匹配的产物；一个都没有时明确失败（缺产物绝不能静默产出空资产）。
pick_apk() {
  local pattern="$1" label="$2"
  local matches=()
  shopt -s nullglob
  matches=( $pattern )
  shopt -u nullglob
  [ "${#matches[@]}" -gt 0 ] || fail "缺少${label}产物：没有任何文件匹配 ${pattern}（构建产物缺失或 artifact 名变化）"
  printf '%s\n' "${matches[0]}"
}

OUT="out"
mkdir -p "$OUT"

ANDROID_APK="$(pick_apk 'dl/pim-android-*/*.apk' '安卓客户端')"
SHELL_APK="$(pick_apk 'dl/pim-shell-android-*/*.apk' '安卓壳')"

ANDROID_ASSET="pim-android-v${VERSION}.apk"
SHELL_ASSET="pim-shell-android-v${VERSION}.apk"
cp "$ANDROID_APK" "$OUT/$ANDROID_ASSET"
cp "$SHELL_APK" "$OUT/$SHELL_ASSET"

# version.json 是客户端更新检查的唯一数据源，URL 用 release 的稳定直链。
BASE="https://github.com/${REPO}/releases/download/${RELEASE_TAG}"
jq -n \
  --arg v "$VERSION" --arg vc "$VERSION_CODE" --arg base "$BASE" \
  --arg a "$ANDROID_ASSET" --arg s "$SHELL_ASSET" \
  '{version: $v, versionCode: ($vc|tonumber), releasedAt: (now|todateiso8601),
    assets: {
      android:      {version: $v, url: ($base + "/" + $a)},
      shellAndroid: {version: $v, url: ($base + "/" + $s)}
    }}' > "$OUT/version.json"

cat "$OUT/version.json"
echo "发布纯逻辑校验通过：资产名与 version.json 已按 $VERSION 生成（未调用 gh，未发布）。"
