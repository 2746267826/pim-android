# 本机完整材料版本

关联[总路线图](https://github.com/2746267826/pim-api/blob/master/docs/product/native-notes-roadmap.md)、
[总 epic #505](https://github.com/2746267826/pim-api/issues/505) 和[材料切片 #37](https://github.com/2746267826/pim-android/issues/37)。
本片基于未合并的混合笔记 PR #36 (`ec59b091ea1e`)，该 PR 又依赖录音 PR #34；
master 基线仍是 `9941ce411740`。本片没有自动合并这些 PR。

这是本地预览版中真实的提交前置能力，**不是上传实现或 M1+M2 完整首版**。
现有后端通用附件仅 4 MB；本片没有修改 OpenAPI、生成 DTO、添加模拟接口或上传假按钮。
Windows 记录、Web、多端冲突、ASR 和 AI 继续由唯一总路线图跟踪。

## 如何体验

1. 从“今日”进入“本机笔记”，新建、离线录音/编辑 Markdown、插入照片和手写。
2. 右上“结束”先等待本篇录音停止，再保存原稿并固定一份本机输入，后台准备材料。
   无网络也能执行；只是本机准备，不是离线上传队列或云处理。
3. 可继续编辑或补录。已固定的 A 不追着当前新稿 B 变化，状态提示尚有未纳入修改。
   再次结束 B 创建新版本，包含同篇全部旧录音和新录音；原样重复结束不新建版本。
4. 点击材料状态行，或更多菜单“材料版本（含全部录音，仅本机）”，查看各版是否准备齐备。
   每次录音会话、用户段和内部 WAV 分片全部覆盖；中断的录音仍保留中断标记。
5. 失败点“重试此版本，不改用新稿”。原稿、请求和已准备材料保留；重试不会开麦或联网。
   系统中断准备任务后由 WorkManager 续作；启动笔记页也对未完成请求补排任务。
6. 从**具体版本**导出完整材料包（含录音），系统选择目标。授权明确后才输出原始音频/照片；
   照片可能含 EXIF，谨慎分享。旧的“不含录音文档包”仍是另一种导出，二者不能混称。

## 数据与恢复边界

- `noBackupFilesDir/note-material-versions/<version UUID>/input.json` 是固定请求：
  版本 UUID/创建时间、精确原稿 JSON、所有已停止的录音会话及段/分片/样本数，不是后端 DTO。
  当前没有服务端 revision、账号或服务器绑定；本机材料不会自动属于当前登录账号。
- 请求先于后台任务持久化。`files.json` 原子记录已准备的文件；只有全部成功后才写 `manifest.json`，
  它是唯一完整标记。固定文件保存在同根 `blobs/<SHA-256>`，不暴露给相机 Provider 或诊断包。
  每个唯一字节序列首次复制、同步落盘，后续版本复用；**不是不占额外空间**。
- 尚未复制的录音通过已停止且不可重用的录音 UUID/段/分片和样本清单引用原私有文件。
  准备前验证 WAV 容器、头和长度，不修写源文件；照片验证原稿中的摘要，手写从固定向量渲染。
  缺失、格式/长度错误、未知扩展或归属不明的损坏录音不能报完整。部分准备的状态不是已提交。
- 完成后只读固定副本，不再读取当前原稿或原附件；原文件后续受损也不能改变完成版本。
  已固定副本若损坏，重试/导出拒绝，不重新哈希当前新稿来掩盖损坏；暂无修复/恢复管理 UI。
- 摘要、音频副本和 ZIP 使用 64 KiB 缓冲，不把全篇录音载入内存。原稿 JSON 和笔迹渲染仍需内存，
  大量正文/笔迹/数十小时材料性能未验收；单次 Worker 的系统时限适用，中断从文件日志续作。
- ZIP 含 `material-version.json`、`materials.json`、`note.json`、`document.md`、相对 `assets/` 和
  `recordings/<session UUID>/manifest.json` / WAV。每文件身份/角色、字节数、摘要与录音段/分片关系可查。
  `note.json` 保留块顺序、身份/尺寸和可编辑向量，图片不是原稿替代。没有包导入或服务端解包。
- 导出会先重新校验全部固定副本；目标失败不会删除原件，但目标可能留下不完整 ZIP。
  未选择具体固定版本时拒绝导出，不在系统选择器返回后偷偷改用当前文档。版本 ID 跨 Activity 重建保留。
- 现在没有删除/GC、存储配额和后台自动上传；不会为了省空间自动清除原始录音、失败请求或旧版本。
  后续必须一起设计保留/删除规则、账号切换、上传同意、长附件契约和服务器完整性确认，不能将本地齐备当云端接受。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon --max-workers=4
$env:ANDROID_SERIAL='<dedicated-emulator-serial>'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.pim.app.notes.NativeNoteMaterialsTest,com.pim.app.notes.NativeNotesDocumentTest,com.pim.app.recording.RecordingCaptureTest,com.pim.app.ui.theme.PimThemeComponentsTest,com.pim.app.ui.today.TodayScreenTest' --no-daemon --max-workers=4
```

实际修订、通过/失败、数量和手工设备证据见 PR，不能以代码存在当通过。
隔离模拟器禁用宿主音频，验证真实 Android AudioRecord/WorkManager/文件管线，不证明声学质量或 OPPO 长时可靠性。
仍未验收 OPPO 手写/掌触与 100/180 分钟及超段、真实掉电/空间耗尽/厂商杀进程、大规模材料性能与无障碍、
账号/服务器绑定及上传网络策略、长附件服务端、ASR/AI、生成稿保护、跨端冲突和 Windows/Web。
