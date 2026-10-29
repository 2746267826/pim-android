# 本机真实 Test API 上传验收

只在工作区所属专用模拟器（本次为 `Codex_Native_Notes_20261025` / `emulator-5556`）运行，先核对AVD名称与数据目录，不重置或操作用户手机/其他模拟器。不要读取 `.env`、生产数据库或真实录音/照片。

## 隔离服务端

`scripts/qa/AndroidUploadApiHost.cs` 是测试源，不属于安卓产品。核对相邻 `pim-api` 的 AGENTS.md 后，在非master开发分支临时放入 `tests/Pim.UnitTests/Notes/TemporaryAndroidUploadHostTests.cs`（已有同名文件时先核对，不覆盖他人改动）。它复用真实Test API、鉴权与材料存储，数据库为InMemory，材料/密钥环使用显式临时目录，仅监听127.0.0.1。

设置 `PIM_ANDROID_UPLOAD_QA_ROOT` 为工作区内独立目录，然后执行：

```powershell
dotnet test tests/Pim.UnitTests/Pim.UnitTests.csproj --filter FullyQualifiedName~TemporaryAndroidUploadHostTests -m:1 /nr:false
```

目录内 `ready.json` 给出本次端口。合成账号 `notes-qa-alice` / `notes-qa-bob` 均为普通用户，密码为测试夹具默认的 `password123`，不是真实凭据。模型未配置，不会发起付费调用。

服务端代理的 `/__qa/fault?kind=part` 或 `complete` 在真实API已保存后丢失一次成功回执，用于验证不确定窗口；`/__qa/summary` 仅输出安全计数。创建临时目录下 `stop` 文件结束服务，默认两小时自动退出。结束后移除API中的临时测试源，不能混入API文档PR或生产包。

## 模拟器与测试

构建app及androidTest包，使用本次端口做 `adb -s emulator-5556 reverse tcp:PORT tcp:PORT`。仅在确认该AVD归本任务所有并已保存必要证据后，才能清理其旧合成测试状态。

```powershell
adb -s emulator-5556 shell am instrument -w -r `
  -e class com.pim.app.notes.NativeNoteUploadTest `
  -e nativeNotesQaApi http://127.0.0.1:PORT/api/v1/ `
  -e nativeNotesQaProfile phone411 `
  com.pim.app.test/androidx.test.runner.AndroidJUnitRunner
```

缺少隔离参数时在Activity启动前跳过本类。必须解析 `OK (N tests)` 与失败/崩溃标志；adb命令退出0不等于测试通过。测试预算为120秒，不自动双击或用扩大预算掩盖失败。

本类覆盖普通账号>4MiB混合材料/固定A与新稿B隔离、完成回执丢失后离开编辑页继续、切账号暂停与原账号明确恢复、页面重建重新同意。录音为合成PCM、照片/笔迹为测试夹具，不是课堂或OPPO验收。

手机默认1080×2424/density420/font1.0；另测640×1280/density320/font1.5与2048×1536/density320/font1.5。保留现有离线编辑/录音/恢复的原断言回归。截图只取本合成测试页面，保存到app外部专属目录 `native-note-upload-qa`，提取后人工检查；不导出加密偏好或完整认证日志。
