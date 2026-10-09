# pim-android

PIM 的安卓采集端：定位轨迹、运动状态与应用使用情况采集。

## 目录

| 路径 | 内容 |
| --- | --- |
| `src/client-android/` | 采集端主体（Kotlin / Gradle 多模块） |
| `src/client-shell-android/` | 旧壳（原生 Kotlin WebView 壳）——**V2 的 Capacitor 壳上线后退役** |
| `scripts/` | 构建与真机取证脚本（`scripts/qa/android-forensics-emulator.sh` 等） |
| `.github/workflows/` | `build-android.yml`、`build-shell-android.yml` |

## 构建

```bash
cd src/client-android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## 数据约定

采集端只做精度过滤，**不丢点、不抽稀、不静默裁剪**；符合精度的点逐条入库、逐条上传，允许积压。

## 相关仓库

| 仓库 | 角色 |
| --- | --- |
| [pim-api](https://github.com/2746267826/pim-api) | 后端 + MCP + 部署 + 契约出口 |
| [pim-web](https://github.com/2746267826/pim-web) | Web 前端 + 双壳 |
| [pim-windows](https://github.com/2746267826/pim-windows) | Windows 守护 + 浏览器扩展 |
| [pim-docs](https://github.com/2746267826/pim-docs) | 文档与归档（private） |

## 贡献约定

所有改动走分支 + Pull Request；提交信息与 PR 描述双语（英文 + 简体中文）。详见 [`AGENTS.md`](AGENTS.md)。
