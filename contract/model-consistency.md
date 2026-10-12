# Android 手写模型 ↔ 契约一致性报告（刷新后）

- 仓：`pim-android`，分支 `deepseek-linux/contract-refresh-master`，base = 本次 fresh `origin/master` `316d68ee89fd00c8d49b1f1d7f3da69b44df7e09`
- 契约：fresh `contract/openapi.json`（2746267826/pim-api@940fe42face873e48c7c36af030d8ca838791471，938271 B，sha256 `4dd7eafb4938…`）
- 本报告**只做核对**，不改任何产品逻辑；不涉及 CI/gate 实施（由后继 writer 合并 webreview 后实现）。

## 0. 结论摘要

1. 契约快照从 258542 B / `1cf26197…`（来源 `pim-api@2f56dbb3`）刷新为 938271 B / `4dd7eafb4938…`（来源 `pim-api@940fe42face8`）。
2. 操作集合与旧快照**完全相同**（315 → 315，`METHOD /path` 集合零增删），变化只在元数据：
   `components.schemas` 112 → 564、响应 content/schema 312 处、security 299 处。
3. **刷新后才第一次可机械判定**：旧快照请求体 `required` 一律为 null、响应模型大多不存在，
   因此下列请求侧 required 缺口与响应侧类型/枚举差异在刷新前无法判定（NOT-VERIFIED，不等于以前没有）。
4. 本次核对以 **ApiService.kt 实际声明的 operation** 为准（相对路径挂在 `/api/v1/` 下），
   不是「模型名 == schema 名」的孤立匹配。
5. **本版为 R1 修订版（`R1-correction`，不重置 review 轮数）**：按独立复核 `reviews/android-windows-models-r1.md`
   补齐 readOnly/writeOnly 元数据与请求/响应方向语义、补报 `syncOutlook` 缺 required 请求体与 `importIcs` 请求体欠描述、
   新增响应容器维度、订正 security 计数与 `register` 连线、重算 coverage 口径。**只改报告，不改产品与契约**；
   快照字节仍为 938271 B / `4dd7eafb…`。
6. 修订后的逐 op 机读证据：`requestBodyAudit` / `responseContainerAudit` / `queryParamAudit` / `container`（见 detail.json）。

## 1. 核对方法与口径

- 操作映射来自 `core/.../network/ApiService.kt` 的注解；响应经 `ApiResponse<T>` / `PagedResult<T>` 结构化解包到真实 payload。
- 客户端侧解析 `core/.../models/*.kt` 的 `@Serializable` 字段：`@SerialName` 决定 wire 名，`@JsonNames` 只影响反序列化别名。
- 序列化器语义由**真实 probe** 实测（不是推断）：见 `commandsrawlogs/02-android-kotlinx-serializer-probe.txt`。
- 判定分级：
  - **确定形状不符**：字段名/类型/required 与契约直接冲突，可用代码路径 + 契约字段确证。
  - **容错范围不同**：够不够宽容（可空、默认值、开放枚举）不一致，行为取决于报文。
  - **响应 subset / unused declaration 观察**：客户端只声明用到的子集、或声明了但没有任何调用点。
  - **需 runtime NV**：需要真实服务/报文才能定论（本任务不跑 API/DB/设备）。

**required 的读取规则（本版新增，必须严格遵守）**：契约里的 `required` 只表示「该属性在 schema 里是必需的」，
它**不区分方向**。当属主 schema 同时标了 `readOnly: true` 时，按 OpenAPI 3.0 语义
`required` **只对响应生效**，该属性 SHOULD NOT 出现在请求里；对称地 `writeOnly: true` 时 `required` 只对请求生效。
因此判定「客户端请求少了契约 required 字段」之前，**必须先看 `readOnly`/`writeOnly`**；
detail.json 里每条 finding 都带 `contractField.{required,readOnly,writeOnly,nullable,default,enum,ref,type}`
与 `requiredSemantics.appliesTo`（`request-and-response` / `response-only` / `request-only` / `n/a`）。

### 1.1 序列化器实测（真实 probe）

```
config: ignoreUnknownKeys=true encodeDefaults=false coerceInputValues=false explicitNulls=true isLenient=false
[1a decode integer overallStatus into String] JsonDecodingException: Expected quotation mark '"', but had '2'
[1c decode integer component status into String] JsonDecodingException (同因)
[2a missing field WITHOUT default] MissingFieldException: Field 'overallStatus' is required … but it was missing
[2b missing field WITH default (emptyList)] OK -> 0
[2c missing String? = null]                 OK -> null
[3a explicit null into non-nullable String (no default)] JsonDecodingException
[3b explicit null into Boolean=false default]            JsonDecodingException  <- 默认值只吸收 missing，不吸收显式 null
[3c missing Boolean=false default]                       OK -> false
[4 write DaemonHeartbeatRequest(all defaults)] {"deviceId":"d","daemonKind":"android","version":"1","serverUrl":"u"}
[5 unknown key] OK
```

要点：`encodeDefaults=false`，所以**取值等于默认值的属性不会出现在请求体里**；这直接决定了第 3 节的请求侧判定。

> 口径修正（M-6）：本 packet 的 probe 镜像只演示了 3 个 heartbeat 默认字段，**漏了 `keyStatsState`**。
> R1 用 12 字段完整镜像独立重跑，`{"deviceId":"d","daemonKind":"android","version":"1","serverUrl":"u"}`
> —— **4 个默认字段都不上线**，机制结论不变。原 probe 日志：`commandsrawlogs/02-android-kotlinx-serializer-probe.txt`；
> 独立复现：`reviews/android-windows-models-r1.logs/04-android-kotlinx-probe.log`。

## 2. 操作级核对表（33/34 存在，1 条悬空）

| # | ApiService | 方法 路径 | 契约存在 | security | 请求模型→契约 schema | 响应模型→契约 payload |
|---|---|---|---|---|---|---|
| 1 | `login` | `POST /api/v1/auth/login` | ✅ | anonymous | LoginRequest → `LoginRequest` | AuthResponse → `AuthResponse` |
| 2 | `register` | `POST /api/v1/auth/register` | ✅ | anonymous | RegisterRequest → `RegisterRequest` | AuthResponse → `AuthResponse` |
| 3 | `refresh` | `POST /api/v1/auth/refresh` | ✅ | anonymous | RefreshRequest → `RefreshRequest` | AuthResponse → `AuthResponse` |
| 4 | `getCalendars` | `GET /api/v1/calendar/calendars` | ✅ | bearerAuth | — → `—` | CalendarResponse → `CalendarResponse` |
| 5 | `createCalendar` | `POST /api/v1/calendar/calendars` | ✅ | bearerAuth | CreateCalendarRequest → `CreateCalendarRequest` | CalendarResponse → `CalendarResponse` |
| 6 | `getEvents` | `GET /api/v1/calendar/events` | ✅ | bearerAuth | — → `—` | EventResponse → `EventResponse` |
| 7 | `createEvent` | `POST /api/v1/calendar/events` | ✅ | bearerAuth | CreateEventRequest → `CreateEventRequest` | EventResponse → `EventResponse` |
| 8 | `updateEvent` | `PUT /api/v1/calendar/events/{id}` | ✅ | bearerAuth | CreateEventRequest → `UpdateEventRequest` | EventResponse → `EventResponse` |
| 9 | `deleteEvent` | `DELETE /api/v1/calendar/events/{id}` | ✅ | bearerAuth | — → `—` | String → `StringApiResponse` |
| 10 | `getTasks` | `GET /api/v1/calendar/tasks` | ✅ | bearerAuth | — → `—` | TaskResponse → `TaskResponse` |
| 11 | `createTask` | `POST /api/v1/calendar/tasks` | ✅ | bearerAuth | CreateTaskRequest → `CreateTaskRequest` | TaskResponse → `TaskResponse` |
| 12 | `updateTask` | `PUT /api/v1/calendar/tasks/{id}` | ✅ | bearerAuth | CreateTaskRequest → `UpdateTaskRequest` | TaskResponse → `TaskResponse` |
| 13 | `deleteTask` | `DELETE /api/v1/calendar/tasks/{id}` | ✅ | bearerAuth | — → `—` | String → `CalendarOperationResult` |
| 14 | `search` | `GET /api/v1/search` | ✅ | bearerAuth | — → `—` | SearchResult → `SearchResult` |
| 15 | `importIcs` | `POST /api/v1/calendar/import-ics` | ✅ | bearerAuth | — → `—` | Int → `ImportReport` |
| 16 | `exportIcs` | `GET /api/v1/calendar/export-ics` | ✅ | bearerAuth | — → `—` | String → `(inline text/calendar)` |
| 17 | `syncOutlook` | `POST /api/v1/calendar/outlook/sync` | ✅ | bearerAuth | — → `—` | String → `OutlookSyncBatchResponse` |
| 18 | `uploadStats` | `POST /api/v1/stats/upload` | ❌ **不存在** | — | — → `—` | — → `—` |
| 19 | `registerMobileDevice` | `POST /api/v1/mobile/devices/register` | ✅ | bearerAuth | MobileDeviceRegisterRequest → `MobileDeviceRegisterRequest` | MobileDeviceDto → `MobileDeviceDto` |
| 20 | `getMobileGaps` | `POST /api/v1/mobile/sync/gaps` | ✅ | bearerAuth | MobileGapRequest → `MobileGapRequest` | MobileGapResponse → `MobileGapResponse` |
| 21 | `uploadMobileUsage` | `POST /api/v1/mobile/usage/events` | ✅ | bearerAuth | MobileUsageEventsUploadRequest → `MobileUsageEventsUploadRequest` | MobileIngestResponse → `MobileUsageIngestResult` |
| 22 | `uploadMobileForensics` | `POST /api/v1/mobile/forensics/events` | ✅ | bearerAuth | MobileForensicsUploadRequest → `MobileForensicsUploadRequest` | MobileForensicsIngestResponse → `MobileForensicsIngestResult` |
| 23 | `uploadMobileLocation` | `POST /api/v1/mobile/location/points` | ✅ | bearerAuth | MobileLocationPointRequest → `MobileLocationPointRequest` | MobileLocationPointDto → `MobileLocationPointDto` |
| 24 | `uploadMobileLocationsBatch` | `POST /api/v1/mobile/location/points/batch` | ✅ | bearerAuth | MobileLocationPointsUploadRequest → `MobileLocationPointsUploadRequest` | MobileLocationPointsUploadResult → `MobileLocationPointsUploadResult` |
| 25 | `getMobileSummary` | `GET /api/v1/mobile/summary` | ✅ | bearerAuth | — → `—` | MobileUsageSummaryResponse → `MobileUsageSummaryResponse` |
| 26 | `getMobileTimeline` | `GET /api/v1/mobile/timeline` | ✅ | bearerAuth | — → `—` | MobileTimelineResponse → `MobileTimelineResponse` |
| 27 | `getMobileQuality` | `GET /api/v1/mobile/quality` | ✅ | bearerAuth | — → `—` | MobileQualityResponse → `MobileQualityResponse` |
| 28 | `getMobileLocationHistory` | `GET /api/v1/mobile/location/history` | ✅ | bearerAuth | — → `—` | MobileLocationHistoryResponse → `MobileLocationHistoryResponse` |
| 29 | `getMobileLocationOverview` | `GET /api/v1/mobile/location/analytics/overview` | ✅ | bearerAuth | — → `—` | MobileLocationAnalyticsOverviewResponse → `MobileLocationAnalyticsOverviewResponse` |
| 30 | `getMobileLocationTracks` | `GET /api/v1/mobile/location/analytics/tracks` | ✅ | bearerAuth | — → `—` | MobileLocationTrackDto → `MobileLocationTrackDto` |
| 31 | `getMobileLocationSegmentPoints` | `GET /api/v1/mobile/location/analytics/segments/{segmentId}/points` | ✅ | bearerAuth | — → `—` | MobileLocationSegmentPointPageDto → `MobileLocationSegmentPointPageDto` |
| 32 | `sendHeartbeat` | `POST /api/v1/daemon/heartbeat` | ✅ | bearerAuth | DaemonHeartbeatRequest → `DaemonHeartbeatRequest` | DaemonHeartbeatDto → `DaemonHeartbeatDto` |
| 33 | `sendEndpointNotificationAction` | `POST /api/v1/endpoints/{deviceId}/notification-actions` | ✅ | bearerAuth | EndpointNotificationActionRequestDto → `EndpointNotificationActionRequest` | EndpointNotificationActionResponseDto → `EndpointNotificationActionResponse` |
| 34 | `getClientLatest` | `GET /api/client/shell/latest` | ✅ | anonymous | — → `—` | ClientShellLatestResponse → `ClientShellLatestResponse` |

## 3. 确定差异（真实形状不符）

### 3.1 `POST /api/v1/stats/upload` 在契约中不存在（悬空声明）

- 客户端：`ApiService.kt:88-89` `@POST("stats/upload")` → `/api/v1/stats/upload`；
  `StatsModels.kt` 的 `UploadBatch` / `AppUsageEntry` 用 snake_case wire 名（`device_id`/`entries`/`package_name`…，
  注意 `UploadBatch` 的第二字段 wire 名是 **`entries`**，`StatsModels.kt:18`）。
- 契约：`/api/v1/stats/*` **一条都没有**（旧快照同样没有）。同类上传是 `/api/v1/pc/keystats/upload`、`/api/v1/pc/tracker/upload`。
- 产品调用点：**0**（`app/src/main`、`core/src/main` 内无 `uploadStats(` 调用；仅在若干测试里作为接口 stub 覆写）。
- 判定：**悬空声明（unused declaration）**，不是活跃故障；但契约里没有的路径不应该留在 `ApiService` 里。
- 风险类别：确定形状不符（路径不存在）+ unused declaration 观察。

### 3.2 请求侧「契约 required、客户端未声明」的字段 —— **全部命中 `readOnly: true`（本版重写）**

下表 4 个 operation / 6 条 finding，命中 **5 个不同契约字段**：契约 `required` 里都有它们，
但**每一个都同时标了 `readOnly: true`**：

| operation | 客户端模型 | 契约字段 | required | readOnly | 契约要求 |
|---|---|---|---|---|---|
| `POST /api/v1/mobile/devices/register` | `MobileDeviceRegisterRequest` | `apiLevel` | true | **true** | `required=[androidVersion, apiLevel, appVersion, brand, deviceId, displayName, manufacturer, metadataJson, model, sdkInt]` |
| `POST /api/v1/mobile/usage/events` | `MobileUsageEventsUploadRequest` | `windowStartUtc` | true | **true** | required 含 `windowStartUtc` |
| `POST /api/v1/mobile/usage/events` | `MobileUsageEventsUploadRequest` | `windowEndUtc` | true | **true** | required 含 `windowEndUtc` |
| `POST /api/v1/mobile/usage/events` | `MobileUsageEventsUploadRequest` | `fallbackSummaries[].totalTimeVisibleMs` | true | **true** | `MobileUsageSummaryDto.required` |
| `POST /api/v1/mobile/location/points` | `MobileLocationPointRequest` | `isMock` | true | **true** | required 含 `isMock` |
| `POST /api/v1/mobile/location/points/batch` | `MobileLocationPointsUploadRequest` | `points[].isMock` | true | **true** | required 含 `isMock` |

原始契约 schema（逐字 dump，`contract/openapi.json`）：

```
MobileDeviceRegisterRequest.apiLevel             {"format":"int32","readOnly":true,"type":"integer"}   且在 required 内
MobileUsageEventsUploadRequest.windowStartUtc    {"format":"date-time","readOnly":true,"type":"string"} 且在 required 内
MobileUsageEventsUploadRequest.windowEndUtc      {"format":"date-time","readOnly":true,"type":"string"} 且在 required 内
MobileUsageSummaryDto.totalTimeVisibleMs         {"format":"int64","readOnly":true,"type":"integer"}    且在 required 内
MobileLocationPointRequest.isMock                {"readOnly":true,"type":"boolean"}                     且在 required 内
MobileLocationPointsUploadRequest.points[].isMock{"readOnly":true,"type":"boolean"}                     且在 required 内
```

**结论方向（重要，纠正此前版本）**：OpenAPI 3.0 规定 `readOnly` 属性 MAY be sent in a response but
**SHOULD NOT be sent in a request**；当它同时出现在 `required` 里时，**`required` 只对响应生效**。
这 5 个字段（连同类里的 `deviceHash`/`osVersion`）是典型的「服务端 DTO 被复用成请求 DTO」的契约生成物特征。

因此**不能**说「客户端确定少了请求侧必填字段」：

- **静态冲突客观存在**（不删除该事实）：这些字段确实不在客户端模型里，契约确实把它们写在 `required` 里；
- **但方向未定**：客户端不发这些字段**可能恰恰是正确行为**（readOnly 属性本就不该出现在请求里）；
  若服务端确实按请求侧 `required` 校验，那是服务端/契约导出侧的缺陷，而不是客户端 bug；
- **两个方向都是 `NOT-VERIFIED(runtime)`**（本任务不跑 API/DB）。
- 同样的口径必须回灌后继工单：preflight 的 **D-2 / D-3** 没有写 `readOnly`，**后继 PR 不得沿用「客户端缺必填」的方向**。

证据：
- 模型位置 `core/.../models/MobileModels.kt:6-17`（无 `apiLevel`）、`62-70`（无 `window*Utc`）、`100-109`（无 `totalTimeVisibleMs`）、`131-147`（无 `isMock`）。
- 真实请求侧调用点：`app/.../mobile/sync/MobileSyncCoordinator.kt:619`（usage）、`:714`（register）；
  定位上传**只有 batch**：`app/.../mobile/sync/LocationUploadCoordinator.kt:185`（`uploadMobileLocationsBatch`）。
  `LocationUploadCoordinator.kt:302` 是 `MobileLocationPointRequest(...)` **构造**，不是上传调用；
  `api.uploadMobileLocation(` 在 `app/src/main`、`core/src/main` 内 **0 调用点**（M-5 修正）。
- 机读：`detail.json.summary.readOnlyRequiredFieldsAbsentInClientRequest`（6 条 finding / 5 个字段名 / 4 个 operation）。

### 3.3 请求侧 default 导致 required 字段可能不上线（probe 实测）

| operation | 字段 | 客户端声明 | 上线行为 |
|---|---|---|---|
| `POST /api/v1/daemon/heartbeat` | `activityWatchState` / `keyStatsState` / `collectionPaused` / `statusJson` | 各有默认值 | 等于默认值时**不发**该字段（实测 `[4]`） |
| `POST /api/v1/calendar/tasks`、`PUT /api/v1/calendar/tasks/{id}` | `priority` | `Int = 0` | 等于 0 时不发；契约 `required` 含 `priority` |

契约 `DaemonHeartbeatRequest.required = [activityWatchState, collectionPaused, daemonKind, deviceId, keyStatsState, serverUrl, statusJson, version]`；
`CreateTaskRequest.required = [priority, title]`。客户端调用点：`app/.../mobile/sync/MobileHeartbeatReporter.kt:47`。
风险类别：容错范围不同 + 需 runtime NV（服务端是否对缺字段回退默认值）。

### 3.4 `GET /api/v1/mobile/quality` 响应：契约整数枚举 vs 客户端 String（高风险）

| 字段 | 契约 | 客户端 |
|---|---|---|
| `data.overallStatus` | `$ref PimHealthStatus` = `{type: integer, format: int32, enum: [0,1,2,3]}` | `String`（`MobileModels.kt:284`） |
| `data.components[].status` | 同上 | `String`（`MobileModels.kt:297`） |
| `data.issues[].severity` | 同上 | `String`（`MobileModels.kt:306`） |

probe 实测 `[1a]`/`[1c]`：整数进 `String` 属性直接 `JsonDecodingException`；`String` 值则 OK（`[1b]`）。
→ **若服务端确实按契约发整数，`GET /mobile/quality` 会在反序列化阶段整体失败。**
是否真发整数需真实响应 → `NOT-VERIFIED(runtime)`；但两侧声明已确定冲突。
客户端调用点：`getMobileQuality` 在 `app/src/main`/`core/src/main` 内**无调用点**（仅声明），
因此当前不会在真实流程中触发，属**待接入前的阻塞级隐患**。

### 3.5 响应形状不符（客户端期望标量、契约返回对象）

| operation | 客户端声明 | 契约 payload |
|---|---|---|
| `DELETE /api/v1/calendar/tasks/{id}` | `ApiResponse<String>` | `CalendarOperationResult` |
| `POST /api/v1/calendar/import-ics` | `ApiResponse<Int>` | `ImportReport`（`{imported, samples, skipped, skippedReasons}`，全 required） |
| `POST /api/v1/calendar/outlook/sync` | `ApiResponse<String>` | `OutlookSyncBatchResponse` |
| `GET /api/v1/calendar/export-ics` | `ApiResponse<String>`（JSON 信封） | content-type `text/calendar`，schema `{type: string, format: byte}`，**不是 JSON 信封** |

这四条在 `app/src/main`、`core/src/main` 内**均无调用点**（unused declaration 观察）。
注意 `deleteTask` 的 payload 类型无法用 `String` 反序列化（对象 → String 失败），一旦接入即坏；标 `NOT-VERIFIED(runtime)` 是就「当前是否有真实流量」而言，不是就形状而言。
容器维度见 §3.12；请求体维度见 §3.11。

### 3.6 `CalendarResponse` / `CreateCalendarRequest` 字段偏差

- 契约 `CalendarResponse.properties = [canEdit, color, eventCount, id, isDefault, kind, name, outlookCalendarBindingId, source]`，
  `required = [color, eventCount, id, isDefault, kind, name]`。
- 客户端 `AuthModels.kt:57-62` 只有 `id/name/color/description`：**多了契约没有的 `description`**，
  **少了 required 的 `eventCount`/`isDefault`/`kind`**（以及可选 `canEdit/outlookCalendarBindingId/source`）。
- `CreateCalendarRequest`（`AuthModels.kt:64-69`）同样发 `description`，契约只有 `name/color/kind`（`name` required，`kind` 可选）。
- 判定：确定形状不符（客户端读/发契约未声明字段；`kind` 缺失影响日历类型）。`createCalendar` 无产品调用点。

### 3.7 事件/任务的响应与请求覆盖缺口（含 `rRule`）

- 契约 `EventResponse.rRule`、`CreateEventRequest.rRule`、`UpdateEventRequest.rRule` 均为 nullable string；
  android 的 `EventResponse`/`CreateEventRequest` **完全没有 `rRule` 字段**（`AuthModels.kt:74-92`）。
  与 web/windows 不同，android 的问题**不是大小写写错，而是根本没有能力表达重复规则**（覆盖缺口）。
- 契约 `EventResponse.calendarId` 为 required，客户端未声明。
- `PUT /api/v1/calendar/events/{id}` 的契约请求体是 `UpdateEventRequest`，客户端复用 `CreateEventRequest`（字段是子集，形状兼容，但 `isAllDay/rRule/attendees…` 全都发不出去）。
- 契约 `TaskResponse` 共 17 个字段，客户端只声明 6 个（`id/title/description/priority/due/status`）；
  契约 `EventResponse` 共 42 个字段，客户端只声明 7 个。
- 判定：响应 subset 观察（非破坏）+ 功能覆盖缺口（需要时才是缺陷）。`getEvents` 有 2 个真实调用点（`ScheduleWindowRepository.kt:254,432`）。

### 3.8 `DaemonSourceState` 枚举少一个值

- 契约 `DaemonSourceState = {enum: [Unknown, Available, Unavailable, Paused]}`，用于 `activityWatchState`/`keyStatsState`。
- 客户端 `DaemonSourceStates`（`DaemonModels.kt:38-42`）只有 `Unknown/Available/Unavailable`，**没有 `Paused`**。
- 字段类型是 `String`，所以收到 `"Paused"` 不会解码失败（probe `[1b]`：String 接受任意字符串），
  但客户端没有任何地方能**发出** `Paused`，也没有对 `Paused` 的展示/判断分支。
- 判定：覆盖缺口（非破坏）。**不把「字符串枚举 vs String」当作违规**——契约是 string enum，客户端开放 String 只是少了校验，不等于实时失败。

### 3.9 查询参数差异

| operation | 差异 | 判定 |
|---|---|---|
| `GET /api/v1/mobile/location/history` | 客户端发 `includeRejected` / `cursor` / `pageSize`，契约该 operation **未声明**这三个参数 | 多余参数（服务端一般忽略）→ 观察项 |
| `GET /api/v1/mobile/location/analytics/{overview,tracks,segments/*/points}` | 契约参数名是 **PascalCase**（`RangeStartUtc`/`DeviceId`/`MaxAccuracyMeters`…），客户端发 camelCase | 仅大小写差异；ASP.NET Core 查询串绑定默认大小写不敏感 → 倾向无影响，但**未运行服务，标 `NOT-VERIFIED(runtime)`** |
| `GET /api/v1/calendar/events` | 契约另有 `search`/`calendarId`，客户端未用 | 覆盖缺口（非破坏） |
| `GET /api/v1/calendar/tasks` | 契约另有 `search/calendarId/status/priority/plannedFrom/plannedTo/dueFrom/dueTo/page/pageSize` | 覆盖缺口（非破坏） |
| `GET /api/v1/mobile/summary` | 契约另有 `rangeStartUtc`/`rangeEndUtc`，客户端只发 `date`/`deviceId` | 覆盖缺口（非破坏） |
| `GET /api/v1/mobile/timeline` | 契约另有 `rangeStartUtc`/`rangeEndUtc` | 覆盖缺口（非破坏） |
| `GET /api/v1/search` | 契约另有 `limit`，客户端未用 | 覆盖缺口（非破坏） |
| `GET /api/v1/calendar/export-ics` | 契约另有 `ids`，客户端未用 | 覆盖缺口（非破坏） |
| `GET /api/v1/calendar/calendars` | 契约有 `kind`，客户端未用 | 覆盖缺口（非破坏） |
| `PUT/DELETE /api/v1/calendar/events/{id}` | 契约有 `scope`/`recurrenceId`/`originalEventId`，客户端未用 | 覆盖缺口（非破坏） |
| analytics overview/tracks | 契约另有 `Timezone`/`IncludeRejected`/`Cursor`/`PageSize`/`force` 未用 | 覆盖缺口（非破坏） |

> 上表为 M-9 补齐后的穷举；机读全量见 `detail.json.queryParamAudit`（逐 op `clientQuery` / `contractQuery` /
> `clientQueryNotInContract` / `contractQueryUnusedByClient`）。**未用参数是不破坏的覆盖缺口，不计为产品缺陷。**

### 3.10 响应侧「契约 required 但客户端没声明」的字段（逐条，非破坏）

服务端一定会发这些字段，客户端只是没接（不会崩）；列出来是为了说明覆盖边界，**不当作缺陷加权**：

| operation | 客户端模型 | 契约 required 而客户端未声明的字段 |
|---|---|---|
| `GET/POST /api/v1/calendar/calendars` | `CalendarResponse` | `eventCount`, `isDefault`, `kind` |
| `GET/POST/PUT /api/v1/calendar/events` | `EventResponse` | `calendarId`, `source`, `uid` |
| `GET/POST/PUT /api/v1/calendar/tasks` | `TaskResponse` | `isInbox`, `sortOrder`, `subTasks`, `uid` |
| `GET /api/v1/search` | `SearchResult` | `moduleName` |
| `POST /api/v1/mobile/devices/register` | `MobileDeviceDto` | `apiLevel` |
| `GET /api/v1/mobile/summary` | `MobileUsageSummaryResponse` | `syncBatches[].rejectedItemCount` |
| `GET /api/v1/mobile/location/analytics/tracks` | `MobileLocationTrackDto` | `segments[].path[].qualityFlags` |

### 3.11 请求体存在性 / required（**本版新增维度，I-2**）

此前版本声称「逐 operation 比过 `requestBody`」，但没有把请求体存在性做成可比对的列，因此漏了两条：

| operation | 客户端 | 契约 | 判定 |
|---|---|---|---|
| `POST /api/v1/calendar/outlook/sync` | `syncOutlook()` **没有任何 `@Body`**（`ApiService.kt:84-85`）→ Retrofit 发出无 body 的 POST | `requestBody.required = true`，schema `OutlookSyncRequest`（`required=["mode"]`，另有 `calendarBindingIds/rangeStart/rangeEnd/retryOfBatchId`） | **`REQUEST_BODY_REQUIRED_MISSING`** —— 契约要求必填请求体、客户端完全不发 |
| `POST /api/v1/calendar/import-ics` | `importIcs(@Body body: RequestBody)`（`ApiService.kt:74-75`）发送原始 ICS 字节 | 该 operation **完全没有 `requestBody`** | **`CONTRACT_REQUEST_BODY_UNDESCRIBED`** —— 客户端发体、契约未描述（契约欠描述，更轻） |

两条都是**死声明（0 产品调用点）**，因此不是活跃故障；但它们是**静态偏差**，必须记录。
机读：`detail.json.requestBodyAudit`（逐 op 的 `clientHasBody` / `contractBodyPresent` / `contractBodyRequired` /
`contractBodySchema` / `verdict`）。

### 3.12 响应容器形状（**本版新增维度，I-3**）

此前版本把 `List<T>`、`PagedResult<T>`、裸对象**都规约到叶子 `T`** 再比较，容器形状从未被比较，
所以这一整类差异在 md 与 detail.json 里都不可见。本版对全部 34 个 operation 建立
`envelope → data → 容器` 三个层次（`detail.json.operations[].container` 与 `responseContainerAudit`）：

| operation | 客户端声明 | 契约 2xx payload | 判定 |
|---|---|---|---|
| `GET /api/v1/calendar/events` | `ApiResponse<PagedResult<EventResponse>>` | `EventResponsePagedResult`（对象） | ✅ 正确对照 |
| `GET /api/v1/mobile/location/analytics/tracks` | `ApiResponse<List<MobileLocationTrackDto>>` | data = array | ✅ 正确对照 |
| `GET /api/v1/mobile/location/analytics/segments/{segmentId}/points` | `ApiResponse<MobileLocationSegmentPointPageDto>` | data = 对象 | ✅ 正确对照 |
| `GET /api/v1/calendar/tasks` | `ApiResponse<List<TaskResponse>>` | `TaskResponsePagedResult`（对象：`items/page/pageSize/totalCount/totalPages`） | ❌ **`RESPONSE_CONTAINER_MISMATCH`**：数组 vs 对象，接上即反序列化失败 |
| `GET /api/v1/search` | `ApiResponse<List<SearchResult>>` | `SearchResultPagedResult`（对象） | ❌ **`RESPONSE_CONTAINER_MISMATCH`**：同上 |

`getTasks`（仅测试 stub）与 `search`（全仓 0 引用）**都是死声明**，所以不是活跃故障；
但「逐字段 + 0 应审未审」的说法在这两条上不成立 —— 容器维度此前确实缺失。

**只新增列，不替换叶子比较**：原 findings 里对 `EventResponse`/`TaskResponse`/`SearchResult` 的字段级比较全部保留；
`deleteTask`/`importIcs`/`syncOutlook` 的「客户端期望标量、契约返回对象」仍按原 `CLIENT_CLASS_MISSING` 记录，不重复计数
（容器审计里标为 `RESPONSE_PAYLOAD_SHAPE_MISMATCH` + 指向该 finding）。
`exportIcs` 契约 2xx 是 `text/calendar`（inline schema），不是 JSON 信封，容器审计标 `RESPONSE_CONTENT_TYPE_MISMATCH`。

## 4. 安全维度（逐 operation，不是「统一加了 header」）

客户端有**两个 OkHttpClient 实例**，这决定了每个 operation 的实际鉴权行为：

| 通道 | 构造 | 是否注入 Bearer |
|---|---|---|
| `refreshApiServiceForServer(origin)` | `ApiClientProvider.kt:29-32,106-108` 用 `refreshOkHttpClient`（**没有 `AuthInterceptor`**） | **不注入** |
| `apiService()` | `ApiClientProvider.kt:59` 用注入了 `AuthInterceptor` 的 `okHttpClient` | 注入（`AuthInterceptor.kt:50-57`） |

- `login` → `CoreModule.kt:46-47` 调 `refreshApiServiceForServer(serverIdentity).login(request)`
  （`ApiClientProvider.kt:29-32` 用 `refreshOkHttpClient`，`:106-108` 无 interceptor）→ **匿名**，与契约 `security: null` 一致，**有真实调用点**。
- `refresh` → `CoreModule.kt:63-64` 调 `refreshApiServiceForServer(serverIdentity).refresh(request)` → **匿名**，**有真实调用点**。
- `register` → **没有任何构造点、也没有任何调用点**（`ApiService` 的 `.register(` 在产品代码 0 命中，
  测试里只有 stub）。它是**第二个 Android 死声明**（与 `uploadStats` 同类）。
  **不能**把它描述成「活跃的匿名调用」或「走 refresh 客户端」—— 源码不支持这条连线。
  若被调用，它会走含 `AuthInterceptor` 的 `okHttpClient`，但从来没人调用。
- `refreshApiService()`（`ApiClientProvider.kt:27`）同样**无调用点**。
- 其余所有 ApiService operation 走 `apiService()` → 默认 `AuthMode.Required`（`AuthInterceptor.kt:17`）→ 从**按 trust origin 分库**的 token store 取 token
  （`accessTokenForServerIdentity`，`AuthInterceptor.kt:26-31`），跨 origin 不会串 token。
- 显式匿名开关只有一处：`ConnectionProbeService.kt:157` 的 `AuthMode.Anonymous` 会 `removeHeader(Authorization)`，
  用于 `GET /api/version`（`versionUrl`，契约匿名 ✅）和 Web 根路径（SPA 兜底，契约匿名 ✅）；
  `POST` 之外唯一的 Required 探测是 `/api/v1/status/summary`（`ConnectionProbeService.kt:91-95`，契约 bearerAuth ✅）。
- `getClientLatest`：契约 `security: null`（**匿名**），客户端走 `apiService()` 仍会带 Bearer（多带 header，非违规）。
- `opsKey`（`X-PIM-Ops-Key`，7 个 `/api/v1/ops/*`）：android **零引用**（全仓 grep 0 命中），无可判偏差。
- **结论（本版订正计数）**：33 个存在的 operation 中，**4 个匿名 / 29 个 Bearer**，与契约逐条一致 ——
  匿名的是 `login`、`register`、`refresh`、`GET /api/client/shell/latest`（§2 表第 34 行本来也标的是 anonymous，
  旧版 §4 写「3 匿名 / 30 Bearer」属自相矛盾，已订正）。
  类型上区分：**declared**（契约声明）4 匿名；**active**（有真实调用点）匿名只有 `login`/`refresh`，
  `register` 是 declared-but-never-wired，`getClientLatest` 是匿名但可能附带 Bearer。
- **未发现**「该匿名却强制 Bearer 导致失败」或「该 Bearer 却漏注」的 active operation。
- **不冒称授权已验收**：以上均为**源码静态连线**，未跑 API/DB/设备 → 授权链路 `NOT-VERIFIED(runtime)`。
- 机读：`detail.json.security`（含 `anonymous` / `activeAnonymous` / `declaredButNeverWired` / `clientSideBehaviour`）。

## 5. 覆盖清单（哪些模型审了、哪些没审，**含本版重算口径**）

计数口径（可独立复算，M-2 修正）：

- `handwrittenModelClasses = 57` = `core/src/main/java/com/pim/core/models/*.kt` 里带 `@Serializable` 的类（可 grep 复算）。
- `mappedAsOperationRoot = 35` = 作为某个已声明 operation 的 `request.clientModel` / `response.clientModel` / `responseLeaf` 出现的客户端类。
- `mappedAsNestedOnly = 17` = 不作为根模型，但从某个根模型的字段类型**递归可达**的客户端类。
- `mappedToAnOperation (52) = 35 + 17`；`notMappedToAnyOperation = 5`；**`57 = 52 + 5` 自洽**。

> 旧版写 `handwrittenModelClasses=57 / mappedToAnOperation=54 / notMappedToAnyOperation=5`，
> `54 + 5 = 59 ≠ 57`，算术不自洽。按上述口径重算为 **52 + 5 = 57**；原值保留在
> `detail.json.coverage.originalAsPublished`（R1 M-2）。

没有任何 operation 指向的（且递归也不可达）5 个：

  - `ApiResponse` （`src/client-android/core/src/main/java/com/pim/core/models/AuthModels.kt`）
  - `AppUsageEntry` （`src/client-android/core/src/main/java/com/pim/core/models/StatsModels.kt`）
  - `IcsImportResponse` （`src/client-android/core/src/main/java/com/pim/core/models/AuthModels.kt`）
  - `PagedResult` （`src/client-android/core/src/main/java/com/pim/core/models/AuthModels.kt`）
  - `UploadBatch` （`src/client-android/core/src/main/java/com/pim/core/models/StatsModels.kt`）

说明：`ApiResponse<T>` / `PagedResult<T>` 是泛型信封，已按结构逐字段核对（`code/message/data/timestamp` ↔ 契约信封）；
`IcsImportResponse`、`AppUsageEntry`、`UploadBatch` 在 `app/src/main`+`core/src/main` 内**无任何引用**，只能按声明核对，无法判定运行时行为。

**未覆盖率：0 个「应审未审」** —— 全部 57 个类都落在「根模型 / 仅嵌套 / 未映射」三档之一，
上述 5 个类属于「无法映射到 operation」，已在报告里显式列出，不冒充已审。

### 5.1 死声明（unused declaration）清单

「声明了但没有任何产品调用点」的 operation / 辅助函数（`app/src/main` + `core/src/main` 计），
**不得**把它们描述成活跃 wire：

| 声明 | 路径 | 状态 |
|---|---|---|
| `uploadStats` | `POST /api/v1/stats/upload` | 契约**不存在**该路径，0 调用点 |
| `register` | `POST /api/v1/auth/register` | 契约存在；**无构造点、无调用点** |
| `refreshApiService()` | `ApiClientProvider.kt:27` | 无调用点 |
| `uploadMobileLocation`（单点） | `POST /api/v1/mobile/location/points` | 0 调用点；真实上传只有 batch（`LocationUploadCoordinator.kt:185`） |
| `getMobileQuality` | `GET /api/v1/mobile/quality` | 0 调用点；接入前即命中整数枚举解码阻塞 |
| `deleteTask` / `importIcs` / `syncOutlook` / `exportIcs` / `deleteEvent` / `createCalendar` / `getTasks` / `search` | 见 §2 表 | 0 调用点 |

机读：`detail.json.deadDeclarations`。**死声明只作静态偏差记录，不计为活跃故障。**

## 6. 未验证 / 阻塞（不得当作已通过）

| 项 | 状态 | 原因 |
|---|---|---|
| 服务端是否按**请求侧** required 校验 §3.2 的 5 个 `readOnly` 字段（即客户端不发是否会 400） | `NOT-VERIFIED(runtime)` | 本任务不跑 API/DB；两个方向都未定（契约生成物特征 vs 服务端真校验） |
| `GET /mobile/quality` 实际返回整数还是字符串枚举（3.4） | `NOT-VERIFIED(runtime)` | 需要真实响应 |
| `encodeDefaults=false` 导致 required 字段缺失时的服务端行为（3.3） | `NOT-VERIFIED(runtime)` | 同上 |
| analytics 端点 PascalCase/camelCase 查询串绑定（3.9） | `NOT-VERIFIED(runtime)` | 未运行服务；ASP.NET 默认大小写不敏感未在本任务实测 |
| `text/calendar` 与 `ApiResponse<String>` 的转换（3.5） | `NOT-VERIFIED(runtime)` | 无调用点，且需真实端点 |
| `syncOutlook` 无 body 的实际服务端响应（3.11） | `NOT-VERIFIED(runtime)` | 无调用点 + 未运行 API |
| 授权链路（4.）是否真的按契约匿名/Bearer | `NOT-VERIFIED(runtime)` | 只有源码静态连线，未抓包/未跑服务 |

## 7. 来源哈希清单（source hash inventory）

```
repo                         pim-android
fresh origin/master          316d68ee89fd00c8d49b1f1d7f3da69b44df7e09
branch                       deepseek-linux/contract-refresh-master  (base = fresh origin/master)
worktree (isolated clone)    /home/coder/workspace/pim-wt/and-contract
upstream repo                2746267826/pim-api
upstream ref                 master
upstream resolved commit     940fe42face873e48c7c36af030d8ca838791471
ref resolution command       git ls-remote https://github.com/2746267826/pim-api.git refs/heads/master
snapshot before              258542 B  sha256 1cf261973833a0690b09d2f2eda916de382359636259ab8ed55a8bb38f1aec98
snapshot before git blob     fd3e464c73b8766de10984553e36e9c2ea2b3018
snapshot before declared src pim-api@master 2f56dbb3 (contract/README.md, stale)
snapshot after               938271 B  sha256 4dd7eafb4938818e13766e7177e1d05f45a4090643f9fe6dcd8d108c4f82af53
refresh command              (cd /home/coder/workspace/pim-wt/and-contract && bash scripts/ci/fetch-contract.sh)                       rc=0
refresh command (pinned)     (cd /home/coder/workspace/pim-wt/and-contract && PIM_CONTRACT_REF=940fe42face873e48c7c36af030d8ca838791471 bash scripts/ci/fetch-contract.sh)  rc=0
pinned == default bytes      true (both 938271 B / same sha256)
```

> 上游 `master` 仍会被后续移动。**未来 check 不应硬锁 940fe42…**；本次 refresh 固定它是为了留下可复现的 provenance。

---

机读详表：[`contract/model-consistency.detail.json`](model-consistency.detail.json)。
复现日志：`remediation/contract-refresh-20261012/android-windows-refresh-models/commandsrawlogs/`。
本版修订证据（R1 I/M 闭环）：`remediation/contract-refresh-20261012/android-windows-models-corrected/`。

**本报告只描述现状，不修产品逻辑；未提交、未推送、未开 PR。**
本版为 **R1 修订版（`R1-correction`）**，不重置 review 轮数；按
`remediation/contract-refresh-20261012/reviews/android-windows-models-r1.md` 的 I-1/I-2/I-3/I-4 与 M-2/M-5/M-6/M-8/M-9/M-11 修订，产品代码与 `contract/openapi.json` 一字未改。
