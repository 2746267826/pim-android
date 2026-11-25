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
- **正式版** → `release.yml`（`Release (promote)`）：改 `VERSION` 开 PR → 自动合并 →
  等该合并提交的构建 → 把那次构建的 pre-release 提升为正式版（`--prerelease=false`
  + `make_latest`）。提升不重新构建，正式版资产就是过完 CI 的那份 APK。
- 只重试提升：`-f promote_tag=android-v26.11.1234`。

> 注意升级方向：正式版的 `versionCode` 来自它被构建时的 run number。若手机里装了
> 更晚的 dev 版（versionCode 更大），系统会把正式版当成降级。测试机请只在需要时
> 装 dev 包，或装之前手动卸载。

## 发版前必须满足

- secret `PIM_RELEASE_TOKEN`（能触发 workflow 的 PAT / App 令牌）：`GITHUB_TOKEN`
  触发的 push / pull_request 不会启动任何 workflow，bump PR 会永远等不到 `CI Gate`。
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
