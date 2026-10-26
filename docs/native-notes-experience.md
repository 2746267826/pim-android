# 原生笔记体验纠偏

本轮针对用户安装本地预览包后指出的 UI 与交付偏差，不继续增加静态样板，也不以底层模块或测试数量代替产品体验。
总需求仍只维护在 [原生客户端与笔记路线图](https://github.com/2746267826/pim-api/blob/master/docs/product/native-notes-roadmap.md)
和 [epic #505](https://github.com/2746267826/pim-api/issues/505)，没有删减手写、云端完整记录/独立分析、Windows 记录或跨端版本规则。

## 基线与范围

- 已重新 fetch，`origin/master` 为 `9941ce411740`；在材料版本 `c7cf58d847ef` 基础上继续真实原生页面。
  仍依赖未合并的录音 #34、混合笔记 #36、材料版本 #38，不自动合并它们。
- 只改变现有 Android 笔记体验与必要回调；没有新增依赖、服务端接口、生成 DTO 或文档磁盘格式。
- 这是可安装的本地预览包，不是 M1+M2 完整首版。上传、逐段 ASR、完整记录和独立分析尚未实现。
  不增加模拟云处理状态、假结果或可点击但无实际效果的上传按钮。

## 真实界面变化

1. 列表展示标题、正文片段、创建时间与照片/手写数量，提供空白页引导、浮动新建入口和返回当前录音。
2. 编辑页移除描边标题框与整排工具。标题直接位于文档内，保存状态轻量显示；无常驻 PIM 品牌栏。
   结束留在右上角，正文保持普通自上而下文档，不把段落包装为一堆卡片。
3. 右下角“+”插入照片、拍照或手写，仍使用当前 Markdown 光标位置。手写书写工具只在书写时展开；
   右下角拖动仍直接控制宽高，放大添空白，不拉伸原始笔迹，也不改用滑块或预设尺寸。
4. 左下角显示开始录音或紧凑浮条，接真实采集峰值；暂停时明确暂停，波形归零，不播放装饰动画。
   键盘出现时浮条与插入入口留在键盘上方，文档末尾保留滚动避让空间。
5. 录音从独立面板逐段回放；“停止这次录音”不结束笔记，仍可继续写和补录。
   整篇“结束”需要确认，成功保存原稿并固定请求后才出现本机保存反馈，材料准备失败不冒充齐备。
6. 材料版本与导出收进次要入口，旧版本隔离、同版重试与完整包导出保留。
   保存失败、录音中断、通知关闭、材料读取失败仍明确提示，不为了简洁隐瞒风险。
7. 宽屏按实际可用宽度显示本篇材料侧栏，可跳转照片/手写；正文限宽。窄屏保留单列文档，
   不是按设备型号写死或把手机布局简单放大。

Markdown 仍是成熟 Markwon 上的**源码编辑 / 阅读预览**，不是已完成所见即所得编辑器；标题、正文与辅助字号、
颜色、间距、浮层、触控尺寸都引用同一个 `PimTheme`。浮条与插入入口复用 `PimFloatingSurface`，状态继续复用 `PimStatusBanner`。
系统栏在本页跟随主题表面，未引入系统悬浮窗权限或重写其他业务页面。

## 验证与边界

`NativeNotesExperienceTest` 覆盖真实 Activity/本地存储/录音服务中的文档空间、浮层避让、键盘、暂停/继续、
预览不改写、结束确认/取消、诚实的本机反馈、停止单次录音与同篇补录。
既有混合文档、相机、手写拖动、活动重建、材料固定/重试/导出继续回归。
实际执行的修订、设备宽度、系统字号、截图与成功/失败记录写在本轮 PR；不能以测试源码存在当作已通过。

示例验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --offline --no-daemon --max-workers=4
$env:ANDROID_SERIAL='<dedicated-emulator-serial>'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.pim.app.notes.NativeNotesExperienceTest,com.pim.app.notes.NativeNotesDocumentTest,com.pim.app.notes.NativeNoteMaterialsTest,com.pim.app.recording.RecordingCaptureTest,com.pim.app.ui.theme.PimThemeComponentsTest' --offline --no-daemon --max-workers=4
```

专用模拟器禁用宿主音频，内容为合成课堂示例；截图来自实际 Android 页面，不使用浏览器样板充当实现。
模拟器布局检查不代替用户认可，也不证明 OPPO 笔/掌触、声学质量、100/180 分钟、厂商杀进程和真实掉电可靠性。
大规模材料性能与完整无障碍、云端闭环和其他平台仍未验收。新包的签名与版本码需由安装器检查，不能为安装而建议删除现有本机材料。
