#!/usr/bin/env bash
#
# PR 元数据门禁（F-A-10 / REQ-COM-05）
#
# 校验当前 PR 的描述是否包含 AGENTS.md 规定的四段双语标题，以及 head 分支命名是否合规。
# 审计验收（findings.json F-A-10）：PR 描述缺章节时 CI 必须变红；模板存在不等于门禁。
#
# 设计约束（见 round4 设计与 review §8 批准的形态）：
#   - 只读当前事件负载：输入来自 workflow 接好的
#     `PR_EVENT_NAME` / `PR_BODY` / `PR_HEAD_REF`，**不查询历史 PR**、不调用 `gh` / GitHub API；
#   - 非 `pull_request` 事件（push master / workflow_dispatch）没有 PR 元数据可校验，
#     本步骤**自行 exit 0** —— 不能靠 job / step 级 `if:` 跳过，被跳过时 required `CI Gate`
#     看到的是 `skipped` 而不是 `success`，master 合并会被自己的门禁挡住；
#   - 四段标题必须是**真实 heading 行**（整行等于标题，允许行尾空白）。
#     只在正文里内联引用（例如「见 `## 测试 / Tests` 章节」）不算满足；
#   - 无 `contents: write`，不用 `pull_request_target`，因此可以在 fork PR 上安全运行。
#
# 失败即非 0：任一标题缺失、分支命名不合规。
set -euo pipefail

EVENT_NAME="${PR_EVENT_NAME:-}"
PR_BODY="${PR_BODY:-}"
PR_HEAD_REF="${PR_HEAD_REF:-}"

if [ "$EVENT_NAME" != "pull_request" ]; then
  echo "非 pull_request 事件（${EVENT_NAME:-<unset>}）：没有 PR 元数据需要校验，步骤自行成功。"
  exit 0
fi

fail=0
missing=""

# 四段双语标题：逐行整行比对（`sed` 去掉行尾空白后 `grep -qxF` 精确匹配整行）。
while IFS= read -r heading; do
  [ -n "$heading" ] || continue
  if ! printf '%s\n' "$PR_BODY" | sed 's/[[:space:]]*$//' | grep -qxF -- "$heading"; then
    missing="${missing}${heading}"$'\n'
    fail=1
  fi
done <<'HEADINGS'
## 技术修改 / Technical changes
## 功能变化 / Feature changes
## 如何体验 / How to try it
## 测试 / Tests
HEADINGS

if [ "$fail" -ne 0 ]; then
  printf '::error::PR 描述缺少必需的双语章节行（AGENTS.md 四段，必须各自单独成行）：\n%s' "$missing" >&2
fi

# 分支命名：agent 分支 {agent}-{os}/{topic}（os ∈ win/linux）、工具自动分支 task/<n>，
# 以及依赖更新工具的 dependabot/** 与 renovate/**（AGENTS.md 的既有工具例外集合）。
if ! printf '%s' "$PR_HEAD_REF" | grep -Eq \
  '^(task/[0-9]+|[A-Za-z0-9][A-Za-z0-9._-]*-(win|linux)/[A-Za-z0-9][A-Za-z0-9._-]*|(dependabot|renovate)/[A-Za-z0-9][A-Za-z0-9._/-]*)$'; then
  echo "::error::PR head 分支 '$PR_HEAD_REF' 不符合 {agent}-{os}/{topic}、task/<n> 或依赖更新工具分支命名（AGENTS.md 分支命名规则）。" >&2
  fail=1
fi

[ "$fail" -eq 0 ] || exit 1
echo "PR 元数据校验通过：四段双语章节齐全，分支命名合规。"
