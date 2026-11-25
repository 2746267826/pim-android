# Ink JSON schema v1（冻结 / Frozen）

本文件冻结 `note.json` 中 `blocks[].kind == "ink"` 的 `ink` 对象。D04 只增加可选的逐笔身份与样式字段，
不提升整篇笔记的 `schemaVersion`（仍为 `1`），也不修改 API/OpenAPI。跨端对照来源是 Web D04 PR #594，
head `12ecc55c2b9e53cf15f1f7139df6d008c1d01af6` 的 `ink-document.ts` 写入形状。

This file freezes the `ink` object for `blocks[].kind == "ink"` in `note.json`. D04 only adds optional
per-stroke identity/style fields, keeps the note `schemaVersion` at `1`, and changes no API/OpenAPI
contract. The cross-client reference is Web D04 PR #594, head
`12ecc55c2b9e53cf15f1f7139df6d008c1d01af6` (`ink-document.ts`).

## 规范形状 / Canonical shape

```json
{
  "width": 320.0,
  "height": 240.0,
  "strokeIds": ["cccccccc-cccc-4ccc-8ccc-cccccccccccc"],
  "strokeStyles": [
    {"color": "#b8373c", "width": 1.8}
  ],
  "strokes": [
    {
      "color": "#b8373c",
      "width": 1.8,
      "points": [
        {"x": 20.0, "y": 100.0, "pressure": 0.2},
        {"x": 300.0, "y": 100.0, "pressure": 0.9}
      ]
    }
  ]
}
```

## 字段 / Fields

| 字段 / Field | 读取 / Read | 写入 / Write | 约束 / Constraint |
|---|---|---|---|
| `width`, `height` | 必填 / required | 总是 / always | 有限数值，宽 `80..2048`，高 `80..4096` |
| `strokes` | 必填数组 / required array | 总是 / always | 最多 10,000 条；每条必须有非空 `points`；所有笔画点数合计 ≤ 50,000（与 Web 编辑上限一致） |
| `strokeIds` | 可选 / optional | 总是（含空数组）/ always, including `[]` | 与 `strokes` 等长、UUID 字符串、唯一；读取时大小写规范化 |
| `strokeStyles` | 可选 / optional | 总是（含空数组）/ always, including `[]` | 与 `strokes` 等长；每项含 `color` 与 `width` |
| `strokes[].color` | 可选 / optional | 总是 / always | `#RRGGBB`；缺少时默认 `#263b85` |
| `strokes[].width` | 可选 / optional | 总是 / always | 有限数值 `0.5..24`；缺少时默认 `2.5` |
| `strokes[].points[].x`, `y` | 必填 / required | 总是 / always | 在画布范围内 |
| `strokes[].points[].pressure` | 可选，缺省 `1` / optional | 总是 / always | `0..1` |
| 根、笔、点、样式项未知字段 / unknown fields | 允许 / allowed | 原样保留 / preserved | 见下方兼容策略 |

当 `strokeStyles` 存在时，它是该笔样式的权威来源；若同笔内联 `color`/`width` 也存在，二者必须一致。
Android 写入时同时写 `strokeIds`、`strokeStyles` 和 `strokes[].color/width`，与 Web #594 当前形状一致。

When `strokeStyles` is present it is authoritative; inline `color`/`width` must match it if both are
present. Android writes `strokeIds`, `strokeStyles`, and inline `strokes[].color/width` together.

## 兼容策略 / Compatibility policy

- **旧快照 / legacy snapshots**：缺少 `strokeIds`、`strokeStyles` 和逐笔样式时，读取为
  `#263b85` / `2.5`；未编辑的原稿字节不重写。首次写入该 ink 块时补齐稳定 UUID 和显式样式。
- **Web #594 新快照**：读取 `strokeIds`、`strokeStyles`、`strokes[].color/width`；保存/重开/派生 PNG
  保持每笔实际颜色和宽度。Android 不修改 Web PR，也不依赖 API 契约变更。
- **未知字段 / unknown fields**：不全局开启 `ignoreUnknownKeys`。Android 使用针对 ink 的解析器，
  根、笔、点及样式数组中的未知字段原样保留并回写；这不是“静默丢弃未知字段”。
- **拒绝而非重写 / fail closed**：已知字段类型错误、数组数量不一致、重复/非法身份、非法颜色、
  宽度/坐标/压力越界、总点数超过 50,000、内联样式与 `strokeStyles` 不一致时，
  `NoteDocument.decode` 拒绝该快照，不静默补默认值或覆盖原文件。
- **版本 / version**：D04 字段是 schema-v1 的可选增量字段；没有新增 `schemaVersion` 2。
  删除或改变这些字段的语义需要 Android/Web 同步评审。

## 派生与测试 / Derivation and tests

PNG/预览从向量按每笔 `color`/`width` 重新绘制，纸面为白色，不使用主题主色；文件名摘要包含样式，
因此样式变化会产生新的派生图。回归证据位于：

- `src/client-android/app/src/test/java/com/pim/app/notes/InkSchemaCompatibilityTest.kt`（纯 JVM 解码/回写）
- `src/client-android/app/src/test/java/com/pim/app/notes/InkToolsTest.kt`（Robolectric 存储、PNG、撤销）
- `src/client-android/app/src/test/resources/notes/ink-web-d04-v1.json`（Web #594 形状）
- `src/client-android/app/src/test/resources/notes/ink-legacy-v1.json`（旧形状）

本文件冻结的是 Android 当前可消费/产出的形状，不等同于真实设备、掌触、OEM 笔或跨端同步已经验证。
