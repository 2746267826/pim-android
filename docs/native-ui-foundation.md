# Android 主题与基础组件

本批按用户决议“统一后方便再改，差不多就进入功能”落地，不建设全量组件库。
需求来源是 [API 仓库的总路线图](https://github.com/2746267826/pim-api/blob/master/docs/product/native-notes-roadmap.md)
及 [视觉与组件复用规则](https://github.com/2746267826/pim-api/blob/master/docs/product/native-notes-visual-foundation.md)；
总跟踪为 [pim-api#505](https://github.com/2746267826/pim-api/issues/505)，本切片为 [#31](https://github.com/2746267826/pim-android/issues/31)。
这里不复制需求台账，避免跨仓各维护一份路线图。

## 唯一主题入口

沿用主入口已有的 `com.pim.app.ui.theme.PimTheme`：

| 来源 | 维护内容 | 使用方式 |
| --- | --- | --- |
| `ui/theme/PimColorTokens.kt` | 蓝色品牌、完整 Material 语义色、成功/警告语义色 | `MaterialTheme.colorScheme`、`PimTheme.statusColors` |
| `ui/theme/PimThemeTokens.kt` | 文字层级、形状、常用间距、区块和触控尺寸 | `MaterialTheme.typography`、`MaterialTheme.shapes`、`PimTheme.dimensions` |
| `ui/theme/PimTheme.kt` | 注入主题资源，不复制颜色与尺寸 | 在主应用边界包裹内容 |

颜色只在主题来源中定义。业务页面引用 `primary`、`surface`、`onSurface` 等语义角色，
不能把蓝色色号或警告底色再写一遍。字号使用 `sp`、几何使用 `dp`，不按 OPPO 型号写死像素。
用户拖动的手写尺寸、采集到的波形等属于运行数据，不属于固定样式档位。

浅色保持现有默认；`PimTheme(darkTheme = true)`可显式用于原生组件验证，
不表示已新增全应用深色设置，也不表示嵌入 Web 内容已支持深色。
中文沿用平台无衬线字体和字体回退，未新增字体下载或授权依赖。

## 少量实际复用

- `PimButton`：委托 Material 按钮行为，统一形状和最小 48 dp 高度，保留禁用/点击语义。
- `PimFactChip`：同一套事实标签边界、间距、颜色和辅助字号，标签与值合成可读节点。
- `PimSection`：沿用已有组件，改为引用共同边界、形状、排版和区块间距。
- `PimStatusBanner`：信息/成功/警告/错误语义，文字与图标同色，窄空间允许换行，状态可被读屏感知。

这些组件已在已有今日状态栏、设置中的区块、嵌入页的操作和未加密连接提示中使用，
不是单独放一个展示页。输入框、对话框、导航等继续复用 Material 与同一主题，
不为包装而包装；其他既有页面按后续实际改动逐步引用尺寸，不在这一批全站重排。

## 验证与边界

`PimThemeTokensTest`验证品牌引用、浅深色文字配对对比度，以及业务 UI 没有散写颜色。
`PimThemeComponentsTest`在设备侧验证真实渲染颜色随资源替换更新、统一间距/触控尺寸、
四类状态、窄空间/放大字号、按钮禁用/点击和事实标签语义。
测试执行结果以对应 PR 的具体修订与输出为准，编译通过不冒充设备侧运行通过。

本批不改 API/契约、数据库、采集、登录或后台生命周期，也没有加入模拟录音按钮。
普通 Markdown、可编辑手写卡片、无常驻品牌栏及右上角悬浮结束，
是后续真实笔记切片的约束，不因主题 PR 而标为已交付。
OPPO 锁屏/切应用、100/180 分钟录音、自动续段和真实笔输入仍需分别验收。
