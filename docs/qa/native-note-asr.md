# 原生笔记逐段 ASR 隔离验收

日期：2026-10-29（Asia/Shanghai）  
范围：普通合成账号、固定材料版本、逐段任务/结果、明确同意、失败恢复、停止、未知回执、身份隔离。

## 安全边界

- 只使用本机真实 Test API 装配与隔离 InMemory 数据库；不连接生产 API、`pim_prod`、sanitized mirror 或用户数据。
- 模型上游是 `AndroidNoteAsrApiHost.cs` 内的 loopback 合成 ASR，只返回固定合成文字。不得把结果称为真实供应商、真实课堂或识别质量验收。
- 合成账号为 `notes-qa-alice`、`notes-qa-bob`，密码是测试夹具默认值；不得写入或复用真实凭据。
- 模型 API key 仅为 `synthetic-note-asr-key-not-secret`，只存在于运行中的隔离 Test API 加密列，不写入 ready/日志/截图。
- host 只监听 `127.0.0.1` 随机端口。Android 仅通过本任务专用 AVD 的 `adb reverse` 访问，不开放局域网。
- 设备只能使用 `Codex_Native_Notes_20261025`。每次运行都由驱动执行 `adb -s <serial> emu avd name` 核实；名称不符立即拒绝。绝不控制 `emulator-5554` 或未知设备。
- 启动模拟器、安装 APK、运行 Gradle 都必须在统筹批准的设备/编译时隙执行。驱动本身不启动 AVD、不调用 Gradle、不清理设备。

## 复用关系

`scripts/qa/AndroidNoteAsrApiHost.cs` 沿用既有 `AndroidUploadApiHost.cs` 的隔离结构：

1. `AuthRegistrationTestHost` 装配真实 Test API 路由、JWT、服务层、材料存储和 InMemory 数据库；
2. loopback 代理把 Android 请求转发到真实 Test API；
3. 独立临时目录保存材料、ASR checkpoint、DataProtection key 和 ManagedStorage；
4. 普通 Alice/Bob、管理员配置账号、停用账号彼此隔离；
5. `/__qa/*` 只提供故障编排和安全计数，不进入 Android 产品代码。

与上传 host 不同，ASR host 额外提供：

- 本机合成 ASR `/v1/chat/completions`；
- 每次实际发送的序号、音频 SHA256、字节数、状态和合成文字；
- 指定第 N 次发送失败、发送延迟、清除故障；
- 丢失一次“真实 API 已保存任务后”的创建回执；
- 手动调用真实 `NoteAsrJob`，用于在 Hangfire 显式停用的 Test 环境中执行生产任务逻辑；
- 将指定片置为 `Unknown` 的 QA 夹具，不自动触发重发。

## 固定夹具

- 合成音频：16 kHz WAV，121 秒，所以恰好 3 个内部片：`60s + 60s + 1s`。
- 固定材料：同一笔记先生成版本 A，再修改为版本 B 并生成版本 B；ASR 从非当前版本 A 发起，验证任务绑定 A 而不是最新稿。
- 账号：Alice 生成/上传材料并创建任务；Bob 不可读取 Alice 的源、任务或片段。
- 目标：一个启用的 `mimo-asr` 合成目标；另有一个 disabled 目标用于确认安全目标目录不会暴露可用入口。
- 上传材料不会自动创建 ASR 任务；host 在 Test 环境禁用 Hangfire，测试先断言合成 ASR 发送次数为 0，再通过 `/__qa/asr/run` 调用真实 job。

host ready 文件中的安全事实包括 API base、合成账号、模型显示名/模型/revision/endpoint/preset/version、3 片预期，不包含 token、API key 或内部文件路径。

## 测试覆盖

| 场景 | 独立证据 |
| --- | --- |
| 普通账号正常启动、3 片逐段结果 | Alice 可创建/读取；真实 API 返回 3 片；合成 ASR 恰好 3 次发送 |
| 上传不自动处理 | 上传完成后任务列表为空、合成 ASR 发送次数仍为 0 |
| 固定旧版本 A 而非当前 B | 任务 `versionId == A`；A 仍为 `isCurrent=false` |
| 明确同意与真实目标可见 | 上传同意提示不等价；UI 显示模型、revision、endpoint、preset/version；未勾选不能提交 |
| 丢失创建回执 | 真实 API 已保存 1 个任务，客户端进入待核对；刷新只 GET 同 TaskId，不自动新建/重发 |
| 失败片恢复 | 第 2 次合成发送返回 503；只选择失败片；成功后发送总数按预期增加，成功片 SHA 不重复 |
| 未知片 | checkpoint/回执状态为 `Unknown`；刷新和再次执行真实 job 均不产生新发送或 TaskId；明确选中未知片并重新确认后才增加一次尝试 |
| 停止保留成功片 | 第 2 片调用中停止；最终不发送第 3 片，已成功文字仍可读 |
| 页面重建/换账号 | Bob 看不到 Alice 卡片且直连 API 为 404/空；回到 Alice 后原任务与文字保留 |
| 本机编辑交接 | 从固定版本 A 打开编辑器，修改出新的本机稿 B，再返回并恢复编辑器；A 的原文与 B 的修改均不丢 |
| HTTP 次数独立佐证 | `/__qa/asr/summary` 统计的是合成 ASR 实际收到的请求，不读取客户端自报状态 |

UI 标签以实现方
`.codex-tmp/asr-client-implementation/android-report.md`
为准，测试不得另造产品语义。关键标签包括 `material-asr-${versionId}`、`note-asr-open`、
`note-asr-consent-check`、`note-asr-submit-${versionId}`、`note-asr-task-${taskId}`、
`note-asr-task-open-${taskId}`、`note-asr-segment-${taskId}-${index}` 和失败片恢复标签。

“未知片”设备场景使用本机合成 QA 故障：先用真实 job 产生成功片并停止
第二片调用，再把 checkpoint 改成与真实持久字段一致的 `Unknown` attempt +
未过期 running receipt，并把剩余片标为 `Blocked`。它验证列表/详情刷新、再次执行
真实 job、无新 TaskId/发送，以及明确恢复只增加一次尝试；它**不代表**实际进程杀死、
断电、网络中断后的供应商未知结果，也不替代真实供应商/Kill/Doze 验收。

## 运行顺序

`5da8` / `6a84` 冻结输入已在 phone 上执行过 6 个 ASR 场景和 3 个 focused
回归；准确结果与失败保留在 QA report。取得新的统筹批准后：

1. 等 API `destinations` 真实导出、Android 契约快照/生成物和产品实现可编译；
2. 申请编译时隙，构建 app 与 androidTest APK；本 QA 驱动不自行占用 Gradle；
3. 申请专用 AVD 时隙，报告 serial、AVD 名、安装和测试命令；
4. 在全新 `qa-runtime` 目录准备 host：

```powershell
pwsh -File scripts/qa/AndroidNoteAsrQa.ps1 -Action PrepareHost `
  -QaRoot C:\Users\a2746\Desktop\AIP\61023cidexpim\.codex-tmp\asr-client-implementation\qa-runtime
```

5. 设备测试显式传 APK，且必须带批准开关；脚本会在安装前核实 AVD：

```powershell
pwsh -File scripts/qa/AndroidNoteAsrQa.ps1 -Action RunDeviceTest `
  -Serial emulator-5556 -ExpectedAvd Codex_Native_Notes_20261025 `
  -AppApk <app-debug.apk> -TestApk <app-debug-androidTest.apk> `
  -ProceedWithOwnedDevice
```

6. 截图只从 `native-note-asr-qa` 专属目录拉到 `deliverables/native-note-asr-qa`；
7. 记录 instrumentation 的 `OK (N tests)` 原文，不能只依据 adb 退出码；
8. 失败时 driver 在清理 reverse 或开始下个 case 前，先在当前 slot 保存 UI 树、截图、前台 activity、本地 ASR task JSON 和 `/__qa/asr/summary`；
9. 先 `StopHost`，其只会删除本次由模板复制且 SHA256 未变化的临时 API 测试源；随后人工核对设备与应用状态。

## 证据分类

- **设备层**：专用 AVD + 真实 Test API + 本机合成 ASR + 合成 PCM。
- **API 层**：由 API 单元/集成测试证明；设备 host 的手动 job 仍执行真实服务与检查点代码。
- **真实模型**：`NOT-VERIFIED`，本夹具没有供应商调用。
- **真实课堂/OPPO/OEM/长时/Doze**：`NOT-VERIFIED`，不得由本夹具销项。
- **生产部署/数据库/API**：`NOT-VERIFIED`，本夹具严格隔离。

准确 revision、命令、原始输出与失败保留在
`.codex-tmp/asr-client-implementation/qa-report.md`。
