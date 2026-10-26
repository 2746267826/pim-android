# pim-android

PIM 的安卓客户端：保留定位轨迹、运动状态与应用使用情况采集，原生业务按路线图分批演进。

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

## 原生笔记与视觉基础

- [跨端总路线图](https://github.com/2746267826/pim-api/blob/master/docs/product/native-notes-roadmap.md)与[总跟踪 #505](https://github.com/2746267826/pim-api/issues/505)共同保留完整需求，安卓先行不取消 Windows、Web、手写或云端闭环。
- [Android 主题与基础组件](docs/native-ui-foundation.md)说明本仓唯一主题入口和复用方式；[主题切片 #31](https://github.com/2746267826/pim-android/issues/31)已通过 PR #32 合并，只覆盖薄视觉基础，不代表录音笔记已可用。
- [原生录音内核与调试验证入口](docs/native-recording-core.md)对应[录音切片 #33](https://github.com/2746267826/pim-android/issues/33)，使用真实采集、本地 WAV、持续通知及输入波形；不是缺手写的笔记成品，也没有云处理。
- [本地混合文档与基础手写](docs/native-mixed-notes.md)对应[切片 #35](https://github.com/2746267826/pim-android/issues/35)，从“今日”打开本机记录，编辑 Markdown、插入照片/可编辑笔迹、右下角拖拽并关联真实录音；本地预览版不代表云端首版或 OPPO 真机已验收。
- [本机完整材料版本](docs/native-material-versions.md)对应[切片 #37](https://github.com/2746267826/pim-android/issues/37)，结束后固定当时全部原材料和录音，后台准备/失败重试、继续编辑不串版、按版本导出含录音材料包；明确未上传，不冒充云端版本保护。
- 新页面引用统一主题和基础组件，不散写色号、不复制控件样式；现有采集和生命周期不依赖视觉层。

## 相关仓库

| 仓库 | 角色 |
| --- | --- |
| [pim-api](https://github.com/2746267826/pim-api) | 后端 + MCP + 部署 + 契约出口 |
| [pim-web](https://github.com/2746267826/pim-web) | Web 前端 + 双壳 |
| [pim-windows](https://github.com/2746267826/pim-windows) | Windows 守护 + 浏览器扩展 |
| [pim-docs](https://github.com/2746267826/pim-docs) | 文档与归档（private） |

## 贡献约定

所有改动走分支 + Pull Request；提交信息与 PR 描述双语（英文 + 简体中文）。详见 [`AGENTS.md`](AGENTS.md)。
