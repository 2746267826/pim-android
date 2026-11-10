# PIM Dev 定位静止联测 / PIM Dev location lab

## 已确认范围 / Accepted scope

基线：用户于 2026-11-10 要求实现定位、惯性传感器同步静止测试，允许使用 PIM dev，包名必须与正式版不同，免登录完成全部测试。暂时没有真机运动测试条件。现有报告没有坐标，只能用于重建速度/精度输入，合成轨迹必须标明来源。

| ID | 预期行为与验证 / Behavior and verification |
|---|---|
| AC-1 | `com.pim.app.dev` 可与 `com.pim.app` 并存；首次打开直接进入中文联测页；免登录开始、停止、模拟回归、复制和分享报告。检查 APK manifest 并在 AVD 全新安装体验。 |
| AC-2 | 在既有 `ForegroundLocationService` 宿主内同时订阅定位和三种惯性传感器；统一使用 elapsedRealtime；定位行和定时行都包含同期传感器窗口。组件测试与 AVD 实际采样。 |
| AC-3 | 记录注册结果、首样本等待、覆盖时长、事件与投递间隔、最新样本年龄、屏幕/省电状态、服务重建；缺失/过期为 Unknown，安静不等于静止。纯逻辑测试与锁屏观察。 |
| AC-4 | 本地留存脱敏 CSV，停止或重开页面可导出；坐标只在内存用于候选算法；测试不写 PIM 上传队列，不依赖账号/API。实际导出检查与源码/装配检查。 |
| AC-5 | 持续速度毛刺、单次坐标跳变、精度交替、失锁、正常匀速及停车回归；无定位的时钟推进不能伪造新证据或每秒清除候选。先执行有效 RED，再实现与故障对照。 |
| AC-6 | 仅 dev 启用免登录实验装配；debug/release 原业务流程与正式高速策略保持原样。构建 dev/debug/release 并检查产物隔离。 |

## 前置检查 / Pre-flight

- 已读取仓库 AGENTS.md、PIM 开发 skill 和当前实现；工作分支 `codex-linux/location-compare-demo`，起始提交 `8741236`，工作区初始干净。
- 无后端接口或契约变更；本地联测不使用账号、数据库镜像或外部业务服务。
- Android SDK、JDK 17、Gradle 缓存及 AVD 已提供；OPPO 真机由用户后续静止联测，运动效果保持 NOT-VERIFIED。
- 假设：用户选择同意精确定位后才能开始；通知、运动、后台定位和电池设置分别显示实际状态，不将可选权限作为登录或启动门槛。
- 服务宿主与前台生命周期复用 PIM，实验固定请求间隔，不把实验当作 PIM 闹钟恢复、同步调度或正式定位策略的端到端验收。
- GitHub CLI 初始未登录；已确认 Git 凭据助手可以认证 GitHub API。凭据只通过进程内环境传给 CLI，不写文件或输出。

## 如何体验 / How to try it

1. 安装 `src/client-android/app/build/outputs/apk/dev/app-dev.apk`，应用名称为「PIM Dev 定位联测」，包名 `com.pim.app.dev`，与正式版 `com.pim.app` 隔离。
2. 首次打开直接进入联测页，无需登录、服务器地址或测试账号。点击「开始静止联测」，在系统弹窗选择精确定位。可选授权通知；不授权通知/运动识别也可进行三种惯性传感器的完整联测。
3. 手机放桌上保持不动：亮屏约 5 分钟，锁屏约 5 分钟，然后解锁回到页面。页面显示定位条数、基线/候选状态、三种传感器近 5 秒覆盖与最新年龄。后台定位与电池优化状态如实显示，可自行通过页面按钮查看系统设置。
4. 点击「停止联测并保留记录」，再选择「复制联测报告」或「分享完整 CSV」。本地记录跨页面/进程保留，开始新一轮会覆盖上一轮，因此先导出需要保留的记录。
5. 「运行六项合成回归」可离线执行，不影响实时联测，包含静止速度毛刺、单次跳点、精度交替、匀速、停车和失锁。结果不代表真机运动验收。

## 设计与边界 / Design and limits

- 复用 PIM 的 `ForegroundLocationService` 宿主，dev Hilt 多绑定提供本地实验，debug/release 为空集合。dev 禁用业务启动恢复/闹钟/通知广播及普通 PIM 入口，Application 不调度业务工作；测试命令不进入上传队列。
- 公共比较算法、设备诊断和传感器探针抽到 `src/location-test-app/diagnostics`，独立 App 和 PIM dev 共用；正式 debug/release 不依赖此库。
- 定位请求间隔 2500 ms、最小回调间隔 1000 ms；惯性传感器明确请求 20000 μs、批处理延迟 0。约每 5 秒及每条定位回调写一行同步 CSV。
- `elapsed_ms` 是记录/投递时刻，`location_elapsed_ms` 是定位事件时刻；惯性窗口截至记录时刻。GPS/Fused 的原始坐标只留在内存，落盘仅保存速度、精度、位移量和传感器汇总。
- 近 5 秒窗口覆盖不足 4000 ms、事件间断、数据过期或明显延迟投递均为 `Unknown`。`Quiet` 仅表示模长波动较小，不产生静止结论，也不输入高速否决逻辑；累计最大缺口与当前窗口最大缺口分别记录。
- `first_delay_ms` 指首个传感器事件时间距本段启动的延迟，并非首次回调投递等待；投递滞后另由 `max_delivery_delay_ms` 记录。
- 服务重建后保留 CSV、另起段号并重建算法/传感器窗口；跨设备重启或超过 1 小时不沿用旧单调时钟会话。没有永久唤醒锁，锁屏采样受 Android/ROM 调度约束，缺口会显式记录。
- 剪贴板报告限制长度，过长时标明截断；分享 CSV 保留完整记录。联测上限一小时，停止后不再订阅定位或传感器。用户强停不承诺自动恢复。

## 可复现命令 / Reproduction

```bash
# PIM dev APK 与相关组件测试 / Dev APK and relevant component tests
cd src/client-android
./gradlew :app:assembleDev :app:testDevUnitTest --tests '*LocationLabStoreTest' --tests '*ForegroundLocationServiceTest' --no-daemon
# Shared algorithm/coverage tests
./gradlew :location-diagnostics:testDebugUnitTest --no-daemon
# Normal variants remain separate
./gradlew :app:assembleDebug :app:assembleRelease --no-daemon
```

独立 App：从 `src/location-test-app` 执行 `./gradlew :diagnostics:testDebugUnitTest :app:assembleDebug --no-daemon`。

## 执行证据 / Execution evidence

- Linux / JDK 17 / Android SDK；AVD `pim361`（Android 36.1，模拟惯性传感器与系统模拟定位，非 OPPO 真机）。本地日志仅包含合成/模拟器数据。
- 有效 RED：`/tmp/pim-location-lab-red.log`，19 项中 9 项失败；其中三项从原候选实现复现心跳清候选、旧窗口每拍续证据、单次跳点确认运动，另外六项验证尚未实现的覆盖统计。
- 正常实现：`/tmp/pim-location-lab-green.log`，21 项通过（13 项算法、8 项覆盖/新鲜度）。补充覆盖整批延迟投递与历史缺口后恢复。
- 故障对照：仓外 `/tmp/pim-location-lab-mutation` 副本临时关闭候选心跳保留与覆盖时长检查，`/tmp/pim-location-lab-mutation.log` 为 21 项中 2 项实际断言失败；仓库源文件没有改为故障版本。
- 首次全量 dev 单测：2276 项，2275 通过、1 失败、0 skipped；失败为 `NoteAutoSubmitSchedulingTest.submitWaitsBehindPreparationAndRevocationLeavesPreparationIntact` 第 65 行（异步准备 worker 状态）。保留失败，不描述成全绿。清理 app 构建缓存后执行 `:app:assembleDev :app:testDevUnitTest --tests '*LocationLabStoreTest' --tests '*ForegroundLocationServiceTest' --tests '*NoteAutoSubmitSchedulingTest'`，81/81 通过（3 个存储、73 个服务、5 个笔记调度测试）；`/tmp/pim-location-lab-final-dev.log`。
- 初次 AVD：全新安装 dev 免登录进入、精确定位授权、现有前台服务运行、3 种传感器约 50 Hz；模拟定位进入 `fused` 回调。脱敏时间轴 55 行，其中定位 20 行、灭屏期间 28 行；最后一条灭屏心跳三种惯性覆盖均为 4980 ms，候选未激活。
- 首轮代码 `65b925d` 的 APK：新安装免登录、运行时定位授权、锁屏继续采样、快速停止/重开、复制报告、系统 CSV 分享面板、停止后强停再打开留存记录，以及页面六项合成回归（6/6 PASS）已执行。进程 `6133` 在回桌面后被测试命令杀掉，sticky 重建为 `6293`，段号从 1 变为 2，记录约 7495 ms 心跳缺口，新段重建证据窗口。导出样本保留两个段号，检查无原始坐标。
- 首轮 dev APK SHA-256：`681bfed28a936a92a849fca600db48e2ba3161d6794410b54ed5916f4b055688`（21691926 字节）。AVD 同时安装 `com.pim.app` 与 `com.pim.app.dev`，未覆盖正式包；debug DEX 无联测入口与候选算法，dev DEX 两者均存在。dev merged manifest 的五个业务 receiver 均禁用。
- 独立 App `:app:assembleDebug` 构建通过；共享测试在 PIM 工程用 `:location-diagnostics:testDebugUnitTest` 再执行也通过。普通 debug 与 release 构建通过，两个 DEX 均无联测入口和候选算法。并行构建 debug/release 曾触发 Kotlin 编译器堆不足；改用串行和 `--max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4096m` 后 release 通过（`/tmp/pim-location-lab-release.log`）。该环境失败未记为通过。
- PR #74 的代码提交 `65b925d`：[GitHub Actions 42851090773](https://github.com/2746267826/pim-android/actions/runs/42851090773) `CI Gate`、resolve-version、两项 Android 构建、契约 freshness/codegen 全部通过；PR release job 按条件不发版。独立 review 先审有效 RED，再审最终生命周期与 dev 隔离，未留 Critical/Important。
- 后续边界检查在 Android 36 AVD 上复现关闭系统定位后开始测试导致 `ForegroundServiceDidNotStartInTimeException`。dev 实验已改为权限允许时先确认 foreground 请求再判断定位开关/会话有效期，正式采集顺序保持原样；新增明确定位关闭与过期会话测试，修复前 2/2 业务断言失败（`/tmp/pim-location-lab-foreground-red-explicit.log`）。
- 修复后 `:app:assembleDev :app:testDevUnitTest --tests '*DevLocationExperimentTest' --tests '*LocationLabStoreTest'` 为 5/5 PASS（`/tmp/pim-location-lab-foreground-green.log`）。重装最终 APK 后关闭系统定位开始：页面可见「系统定位未开启」，`requested=false`，PID `7075` 持续存在；恢复定位后开始、停止、复制和 CSV 分享再次执行。最终 APK SHA-256：`eb9ab464be72e78b9913d78de3ebc04507a8ac77470879f825a32913fdef1aac`，21692004 字节。前台错误路径修复经独立复审，未留 Critical/Important。
- 契约 Kotlin 生成校验与上游契约新鲜度校验均通过；不涉及接口变更。
- OPPO 真机亮屏/锁屏长时静止与真实行驶识别、停车退出：`NOT-VERIFIED`。静止测试不能证明真实行驶识别率或停车退出时延。
