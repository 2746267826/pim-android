# 发版通道（显示版本号 / dev 版 / 正式版）

本仓与 `pim-api` 用同一套规则：机器版本负责比较，显示版本只负责好看。

## 两个版本号

| 号 | 来源 | 用途 |
| --- | --- | --- |
| 显示版本 `x.y.z` | 仓根 `VERSION` 文件（一行） | 只给人看：`BuildConfig.DISPLAY_VERSION`，设置页 / 版本行 |
| 机器版本 `26.11.1234` | `GITHUB_RUN_NUMBER`（叠历史补偿 +1000） | `versionName` / `versionCode` / `version.json`：更新检查与安装降级判断只认它 |

`versionName` 保持机器号，因为 `UpdateChecker.isNewer` 比较的是它的**最后一段数字**；
把显示号塞进 `versionName` 会让更新检查彻底失效。显示号单独走 `BuildConfig.DISPLAY_VERSION`。

## 两条通道

- **合并到 master** → 照常构建并发 release，但 `--prerelease`。GitHub 的 `/releases/latest`
  天生跳过 pre-release，所以服务端更新检查（`releases/latest/download/version.json`）
  始终指向最近一个正式版，开发期不会给手机推一堆"新版本"。
- CI 创建 release 时显式 `--target <本次 commit SHA>`，tag / release / 资产全部绑定到
  同一个 commit；`version.json` 还会记录 tag、`gitSha`、`displayVersion`、`runId`、
  `runNumber` 和两个 APK 的 `sha256` / `size`，提升前会重新下载核对。
- **正式版** → `release.yml`（`Release (promote)`）：改 `VERSION` 开 PR → 自动合并 →
  等该合并提交的构建 → 把那次构建的 pre-release 提升为正式版（`--prerelease=false`
  + `make_latest`）。提升不重新构建，正式版资产就是过完 CI 的那份 APK。
- 只重试提升：`-f promote_tag=android-v26.11.1234`。

## 发布守卫

- CI 的 `release` job 与 `release.yml` 的 `apply` job 共用 concurrency group
  `pim-release-mutation`：自动发布 pre-release 与正式版提升不能并发，否则提升后
  旧 run 重跑可能把正式版改回 pre-release、让 `/releases/latest` 回退。
- `scripts/ci/release-publish-guard.sh` 在发布前判定：只允许 master；正式版绝不改写；
  已存在 release 必须属于本次 commit；重跑只有在该 commit 仍是当前 master HEAD 时
  才允许刷新/补发，否则 `publish=false`（过期 run 不补发）。
- `scripts/ci/validate-promote-target.sh` 在提升前校验 tag ref → commit SHA、
  release `targetCommitish`、tag commit 与当前 master 的 `VERSION`、成功的 master
  push 构建 run number / `headSha`、`version.json` 绑定、资产 sha256/size，以及
  `/releases/latest` 不会回退。`promote_tag` 恢复路径同样走完整校验，没有绕过口。
- 提升必须使用 REST 数字 `databaseId`（`gh release view --json databaseId`）调用
  `make_latest`；GraphQL 节点 id（`RE_...`）拼进 REST 路径会 404。提升完成后会重新
  读取 release 本体、全部资产和 `/releases/latest`，不一致就失败。
- `release.yml` 使用 `scripts/ci/merge-release-pr.sh`：仓库未启用 auto-merge 时立即
  失败，只有 GitHub 回报 `MERGED` 且合并提交的 `VERSION` 正确才继续，不会把
  “PR 还开着”当成成功。

> 升级方向只在一种情况下要注意：用 `promote_tag` 追发**较旧的构建**时，它的
> `versionCode` 会小于之后合并产生的 dev 包。手机里若已装了那些更晚的 dev 版，这个正式版
> 既不会被提示为更新（版本号更小），手动装 APK 还会被系统按降级拒掉
> （`INSTALL_FAILED_VERSION_DOWNGRADE`）。正常走「改号 → 合并 → 提升」时，提升的就是最新
> 那次构建、`versionCode` 最大，不存在这个问题。

## 发版前必须满足

- secret `PIM_RELEASE_TOKEN`（能触发 workflow 的 PAT / App 令牌）：`GITHUB_TOKEN`
  触发的 push / pull_request 不会启动任何 workflow，bump PR 会永远等不到 `CI Gate`。
- 仓库需允许 auto-merge；否则 release workflow 会在 bump PR 阶段明确失败，不会
  空等或误报成功。
- 发行说明由 `scripts/ci/validate-release-notes.sh` 机械校验：不能为空、不能留占位符、
  必须有列表项、引用的每个 `#PR` 都必须真实且已合并。

## 发版

```bash
gh workflow run release.yml --repo 2746267826/pim-android --ref master \
  -f version=1.1.0 -f notes="$(cat notes.md)"

# 只重试提升
gh workflow run release.yml --repo 2746267826/pim-android --ref master \
  -f version=1.1.0 -f promote_tag=android-v26.11.1234 -f notes="$(cat notes.md)"
```

## 仍未验证的边界

- 真实 `release.yml` dispatch、真实 `PIM_RELEASE_TOKEN`、真实 `/releases/latest`
  变化与正式版提升：由实际发版时验证；CI 只证明守卫脚本与工作流接线。
- 真机安装/升级（签名、`versionCode` 单调、`INSTALL_FAILED_VERSION_DOWNGRADE`）：
  不在 CI 范围内。`promote_tag` 追发较旧构建时仍可能遇到 versionCode 回退，见上文。
