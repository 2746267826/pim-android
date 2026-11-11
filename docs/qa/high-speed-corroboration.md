# 高速定位算法正式接入 / Production high-speed corroboration

用户于 2026-11-11 明确要求将已静止对照的候选算法上线；此前确认不再扩展通知/闹钟保活测试，保留正式 PIM 现有保活。真机运动条件暂不可用，这一限制继续如实记录。

## 范围与验收 / Scope and acceptance

| ID | 行为 / Behavior | 验证 / Verification |
|---|---|---|
| AC-1 | 新鲜定位速度 ≥8 km/h 持续 10 秒，同时取得连续位置佐证后进入高速；累计期间保留 2.5 秒请求。仅速度缺失才允许推算速度作为来源。 | 纯逻辑与正式服务组件测试；模拟轨迹 |
| AC-2 | 位置至少 4 点、跨度 ≥8 秒、净位移 ≥max(20m, 2×中位水平精度)、推算 ≥8 km/h、一致度 ≥0.55；相邻有效点 ≤7.5 秒，至少 3 个移动片段且占比 ≥60%。固定点速度毛刺、单跳点、低速伴漂移不能确认高速。 | 静止/漂移/正常匀速/缺速度回归 |
| AC-3 | 使用位置的单调时间；位置年龄 >5 秒、时间缺失/未来/乱序/重复不能驱动实时策略；中断重建证据，心跳不充当新位置。数据落库/上传规则不变。 | 明确延迟批次、乱序、重复、跨间断重启测试 |
| AC-4 | 连续静止位置簇或可信低速约10秒退出；25秒无有效高速位移退出；停止采集立即复位。惯性安静/Walking 标签不得否决匀速移动。 | 停车、无定位、暂停与运动标签回归 |
| AC-5 | 正式位置回调逐点输入算法，状态机与策略在 Main 串行；状态通知、2.5秒主流随真实状态变化。保留通知、闹钟、恢复、同步和业务接口。 | ForegroundLocationService 全流程组件测试，AVD可行的真实定位链路 |
| AC-6 | 按既有PR与CI Gate合并后发版；正式包不包含联测入口/免登录/dev隔离配置。 | debug/release 构建、独立review、PR checks、master release与产物检查 |

候选阈值来自已交付对照 App；本轮只增加实时新鲜度/连续性处理，并把候选接入原主链路。合并发布由本次「上线」指令授权，仍经过 PR 与 CI，不绕过任何门禁。

无位置佐证的候选最多确认30秒，随后回普通策略并等待30秒再试；这期间若出现连续真实位移佐证，可重新开始确认。位置差推算与GPS速度来自同一Fused源，不视为两个独立定位系统。静止簇检查还约束净移动速度低于7 km/h，避免水平精度接近30m时把正常8 km/h附近的匀速轨迹当成静止。

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
- AVD设备测试正在执行，仅测试系统模拟定位与实际APK服务/通知/入库；OPPO真实行驶识别率与停车时延保持 `NOT-VERIFIED`，不要求用户补测静止或保活。
