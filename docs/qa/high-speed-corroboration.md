# 高速定位算法正式接入 / Production high-speed corroboration

用户于 2026-11-11 明确要求将已静止对照的候选算法上线；此前确认不再扩展通知/闹钟保活测试，保留正式 PIM 现有保活。真机运动条件暂不可用，这一限制继续如实记录。

## 范围与验收 / Scope and acceptance

| ID | 行为 / Behavior | 验证 / Verification |
|---|---|---|
| AC-1 | 新鲜定位速度 ≥8 km/h 持续 10 秒，同时取得连续位置佐证后进入高速；累计期间保留 2.5 秒请求。仅系统速度缺失时允许位置推算作为进入高速的速度来源。已激活后，连续位置证据仅在系统速度缺失或 ≥7 km/h 时续期；系统速度 <7 km/h 的点进入 60 秒退出宽限，缺速度时用位置推算判断低速。候选期单个低速点在 3 秒内不重置确认。 | 纯逻辑与正式服务组件测试；模拟轨迹 |
| AC-2 | 位置至少 4 点、跨度 ≥8 秒、净位移 ≥max(20m, 2×中位水平精度)、推算 ≥8 km/h、一致度 ≥0.55；相邻有效点 ≤7.5 秒，至少 3 个移动片段且占比 ≥60%。固定点速度毛刺、单跳点、低速伴漂移不能确认高速。 | 静止/漂移/正常匀速/缺速度回归 |
| AC-3 | 使用位置的单调时间；位置年龄 >5 秒、时间缺失/未来/乱序/重复不能驱动实时策略；中断重建证据，心跳不充当新位置。数据落库/上传规则不变。 | 明确延迟批次、乱序、重复、跨间断重启测试 |
| AC-4 | 静止位置簇至少 5 点且跨度 ≥15 秒才成立；静止或低速证据持续 60 秒后退出，退出后 90 秒内按 30 秒间隔观察并允许重新确认。25 秒无有效高速位移退出（低速宽限计时期间不触发）；停止采集立即复位。惯性安静/Walking 标签不得否决匀速移动。 | 停车、无定位、暂停与运动标签回归 |
| AC-5 | 正式位置回调逐点输入算法，状态机与策略在 Main 串行；状态通知、2.5秒主流随真实状态变化。保留通知、闹钟、恢复、同步和业务接口。 | ForegroundLocationService 全流程组件测试，AVD可行的真实定位链路 |
| AC-6 | 按既有PR与CI Gate合并后发版；正式包不包含联测入口/免登录/dev隔离配置。 | debug/release 构建、独立review、PR checks、master release与产物检查；状态 PASS，见下方发布记录 |

候选阈值来自已交付对照 App；本轮只增加实时新鲜度/连续性处理，并把候选接入原主链路。合并发布由本次「上线」指令授权，仍经过 PR 与 CI，不绕过任何门禁。

无位置佐证的候选最多确认30秒，随后回普通策略并等待30秒再试；这期间若出现连续真实位移佐证，可重新开始确认。位置差推算与GPS速度来自同一Fused源，不视为两个独立定位系统。静止簇检查还约束净移动速度低于7 km/h，避免水平精度接近30m时把正常8 km/h附近的匀速轨迹当成静止。

退出后的 90 秒恢复观察窗只把**主流**定位间隔降到 30 秒。冲刺（sprint）节奏沿用既有规则跟随策略间隔（`sprintEnabled` 默认开启、窗口 30 秒、1 秒采样，窗口未结束时下一拍接续），因此该窗口内的实际采样是「30 秒主采样 + 冲刺链」，并非纯 30 秒一拍；每轮最长 90 秒，代价有界。此处不为恢复窗新增跳过码：新增码会改动冲刺台账与前端展示口径，收益不抵成本。

## 前置检查 / Pre-flight

- 分支 `codex-linux/high-speed-corroboration`，基于 `origin/master=f0be889`，初始干净。联测 PR #74 仍开放；本发布不合并其 dev 装配。
- 已读 AGENTS.md 与 PIM skill。用户聊天中的明确范围作为本次需求基线，无外部工单；按仓库允许直接在 PR 描述需求与验收。
- 主链路基线只传最新速度，在策略 reduce 时观察，会丢掉逐点位置与单调时间；本次改为位置回调喂点、reduce 只推进超时。
- 不改契约、DTO、后端、通知/闹钟保活或工作流；不访问生产数据库，不把用户CSV或其提取物提交/上传。
- 本地 JDK17、Android SDK、Gradle缓存可用。需要启动 AVD 验证设备可行流程；OPPO运动与停车时延继续 `NOT-VERIFIED`，不要求用户追加静止测试。

## 执行记录 / Evidence

- Linux / JDK17 / Android SDK36.1；既有静止CSV仅用于缺陷证据，不提交、不上传，不声称含有可回放的原始轨迹。
- 有效RED：`/tmp/pim-highspeed-red-clean.log`，15项执行、11项业务断言失败。首次分支切换遗留Hilt生成物的编译失败已通过clean解决，不作为RED。
- 独立review复现确认档没有上限与低速计时跨采样缺口；新增对应2项断言先失败（`/tmp/pim-highspeed-review-red.log`），修复后转绿。预热单调时间丢失的1项断言先失败（`/tmp/pim-highspeed-timestamp-red.log`），补内存字段后通过，上传负载构造未改。
- 相关测试和APK构建：`:app:testDebugUnitTest --tests '*HighSpeedReleaseTest' --tests '*HighSpeedTrackerTest' --tests '*LocationPolicyEngineTest' --tests '*ForegroundLocationServiceTest' --tests '*LocationAcquisitionCoordinatorTest' :app:assembleDebug :app:assembleDebugAndroidTest`，174/174 PASS，0 failures/errors/skipped（`/tmp/pim-highspeed-targeted-build.log`）。对应21算法发布回归、10基础状态、32策略、73服务、38采集协调器。
- 旧纯速度/60秒规则测试已按用户批准的新算法替换，不声称只是行为等价重构；保留匀速、运动标签不否决、停车、重置与失锁的业务断言。
- 仓外故障对照：`/tmp/pim-highspeed-fault-control.log`，实际编译生产tracker与21项发布测试；正常版本21/21 PASS，去掉位移佐证后5项FAIL，去掉新鲜度校验后1项FAIL，仓库实现未被改成故障版本。
- 独立复审：`/tmp/pim-highspeed-rereview.md`，原两项Important已关闭，无剩余Critical/Important。最终提交还需CI与真实APK链路证据。
- 契约codegen与上游freshness校验通过；不涉及接口变更。
- AVD Android 16 API 36 正式客户端 instrumentation：`HighSpeedProductionPlatformTest` 1/1 PASS，97.781s；授权定位/通知与mock-location shell权限，不登录。实际启动生产 `ForegroundLocationService` 和 Fused GPS test provider，固定坐标+20m/s毛刺持续40s始终不Active；停止/重启采样流后注入连续25m/2.5s的32km/h轨迹并进入Active，通知显示“高速轨迹记录中”，再次停止清除高速态。输出：`src/client-android/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5580 - 16-_app-.xml`。这是 Android framework/GMS emulator模拟定位测试，不等于OPPO真实行驶。
- 正式 debug APK 与 instrumentation APK已构建；本地release unsigned APK亦构建通过（8m11s，lintVitalRelease通过），包名 `com.pim.app`，DEX含 `HighSpeedTracker`，不含dev联测入口或候选库。release unsigned SHA-256 `b4a89ca93b13ef279b0635310fb019c2d76fbc349d793bc9925a52bb8c6189c7`。签名正式产物与更新资产由 master CI release负责。
- 本地提交`17960de`包含算法与接入，`4544367`收敛边界，`cee2e66`和随后的文档提交更新测试夹具/证据。PR head的必需CI仍在运行；契约生成与freshness已通过。CI Gate通过后走正常PR合并；合并后确认master release和发布资产。
- OPPO真实行驶识别率与停车退出时延保持 `NOT-VERIFIED`。用户确认暂不扩展保活测试；正式通知与闹钟保活代码未改。无需追加静止或保活实测。
- AC-6 已完成：PR #75 合并提交 `5577b3e`；master CI workflow run `43011276037` 的 `CI Gate`、必需构建与契约检查均通过；release `android-v26.11.1183` 已发布。
- PR #75 review follow-up：30秒静止/速度毛刺保持高速档；候选期单个7.8 km/h点容差；持续低速退出后的90秒恢复窗使用30秒定位间隔。实现与回归由 follow-up PR #76 覆盖，真实OPPO行驶仍为 `NOT-VERIFIED`。
