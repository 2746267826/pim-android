# contract/openapi.json

本文件是 **`pim-api` 仓契约的只读快照**，仅供本仓开发时对照，**不要手工编辑**。

- 来源：`2746267826/pim-api` 的 `contract/openapi.json`（该仓 CI 从 Test 实例导出并硬校验与代码同步）
- 快照取自：`pim-api@940fe42face873e48c7c36af030d8ca838791471`（`master` 解析于 2026-10-12，938271 字节）
- 文件 sha256：`4dd7eafb4938818e13766e7177e1d05f45a4090643f9fe6dcd8d108c4f82af53`

> 上面这三行是**人读摘要**；机器可读的来源记录在同目录 [`provenance.json`](provenance.json)
> （由刷新脚本写出，`repo`/`ref`/`resolvedRef`/`sourcePath`/`bytes`/`sha256`，无时间戳、可复现）。
> 快照字节一旦变化，`provenance.json` 的 `bytes`/`sha256` 必须同步变化 —— CI 会交叉核对两者。

## 为什么要放进本仓

本仓需要调用后端接口，但**不要凭猜测调用**。契约里有什么，才能调什么；契约里没有的，
就不要写进代码 —— 那是「需要新接口」的信号，按 `AGENTS.md` 的「接口契约与门禁」一节
把需求交给用户，由后端在 `pim-api` 实现并更新契约。

## 新鲜度是被 CI 校验的

`CI` workflow 里有一个独立 job **`Contract freshness`**（`contract-freshness`），它在
`push`(master) / `pull_request` / `workflow_dispatch` 上**都**执行，并被 `CI Gate` 逐个 check：

1. 把 `PIM_CONTRACT_REF`（默认 `master`）**解析一次**为不可变的 40 位 commit SHA，
   按该 SHA 取回上游 `contract/openapi.json` 的原始字节；
2. 与本仓 `contract/openapi.json` **逐字节比较**（不是比结构、不锁 `info.title`）；
3. 校验 `contract/provenance.json` 存在、字段形状合法，且与**仓内快照**一致
   （`resolvedRef` **允许落后**于上游 HEAD —— 上游出现非契约提交不会误判）。

任一不一致、或取不到权威源（网络失败 / 404 / 空体 / 非 OpenAPI3）→ **非 0**，CI 红。
**所以「快照落后」现在是会让本仓 CI 变红的**（这是预期行为，不是故障），修复方式就是重新刷新。

来源仓与 ref 可配置（`PIM_CONTRACT_REPO` / `PIM_CONTRACT_REF` / `PIM_CONTRACT_RAW_BASE` /
`PIM_CONTRACT_SOURCE_PATH`）；默认值就是真实上游 `2746267826/pim-api@master`，CI 不设任何覆盖。
把仓内文件当权威源的**测试旁路在 CI 下会被直接拒绝**（配置错误，不是静默变绿）。

> 本仓从 2026-10 起与 pim-web 同构：**客户端 DTO 由契约生成、生成物随提交进仓**。
> 生成物是 `src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt`，
> 由 `scripts/ci/check-contract-kotlin.sh` 在 CI 里逐字节复核（见下文「生成物与门禁」）。
> 本设施刷新/校验的输入仍然是**两个**文件：`contract/openapi.json` 与 `contract/provenance.json`；
> 生成步骤只用 python3 标准库，不安装 npm，也不在 CI 里跑 gradle。

## 怎么刷新

后端契约更新后（用户告知，或你发现契约里缺了已有字段）：

```bash
scripts/ci/fetch-contract.sh          # 刷新 contract/openapi.json + contract/provenance.json
scripts/ci/generate-contract-kotlin.sh # 用新快照重新生成 Kotlin DTO
```

刷新脚本会把**两个**产物先写在仓外临时目录、全部校验通过后才安装进仓：
`contract/openapi.json`（上游原始字节）与 `contract/provenance.json`（来源记录）。
**刷新后请一并提交这三个文件（快照、来源记录、生成的 Kotlin DTO）**，并在 PR 里说明刷新原因。
若解析 ref / 取源 / payload 形状任一失败，脚本**一个字节都不写**，原有产物保持原样。

只读校验（CI 用的就是它，绝不写仓）：

```bash
scripts/ci/check-contract-freshness.sh   # 快照是否与上游一致
scripts/ci/check-contract-kotlin.sh      # 生成物是否与仓内快照一致
```

行为测试（离线 fixture，证明一致=绿 / 陈旧=红 / 取不到源=红 / 旁路被拒 / 生成物漂移=红）：

```bash
scripts/ci/test-contract-freshness.sh
scripts/ci/test-contract-kotlin.sh
```

「谁写」= `fetch-contract.sh` + `generate-contract-kotlin.sh`（人工 refresh）；
「谁只读」= `check-contract-freshness.sh` + `check-contract-kotlin.sh`（CI）。

## 生成物与门禁

- **生成物**：`src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt`
  （包 `com.pim.core.contract`）。文件头带「本文件由工具生成，请勿手工修改」，
  **手工改动会被 CI 的 `Contract codegen freshness` job 逐字节比对抓出来**。
- **生成什么**：`scripts/contract/client-operations.json` 里声明的客户端 operation 的 schema 闭包
  （当前 33 个 operation → 99 个类型）。规则是机械的：`required` 且不属于请求方向的 `readOnly`
  → 非空、无默认值；其余 → 可空 `= null`；string enum → Kotlin enum（`@SerialName` 固定 wire 值）；
  整数 enum → `typealias` 到 `Int`（契约没给成员名，不臆造）。
  清单里写了契约中不存在的 METHOD/路径 → 生成器**直接报错**，不会产出半张契约。
- **CI 校验**：job `contract-codegen`（`Contract codegen freshness`）在 push/pull_request/workflow_dispatch
  上都执行，并接进 `CI Gate` 与 `release` 的 `needs`：用仓内快照重新生成一次，与仓内生成物逐字节比对。
- **编译期门禁**：gradle 任务 `verifyContractEndpoints`（`src/client-android/build.gradle.kts`）
  挂在 `:core` / `:app` / `features:calendar` 的编译任务之前，把源码里每个 Retrofit 注解与契约核对：
  端点不存在 / 方法不对 / `@Path` 名字与路径模板不符 / `@Query` 名字契约里没有 /
  `@Body` 或返回类型与该 operation 的 schema **结构**不一致（多包一层 `List<...>`、换成 `String`
  这类非契约类型都算）→ **编译失败**。查询参数名按大小写不敏感比对（契约里 analytics 系参数是 PascalCase，
  客户端发 camelCase）。
  它用文本扫描而不是 Kotlin PSI，因此对"看不懂的东西"一律 fail-closed：解析不出来的注解形态、
  `@HTTP(...)`、`@Url`、参数注解（`@Path`/`@Query`/`@Body`）解析数量对不上、
  以及**用本地同名 `typealias`/`class`/`interface`/导入改名顶替契约类型名**都会被拦下。
- **门禁自带自检**：任务每次运行都会先把 fixture 写进临时目录、**走同一个文件扫描入口**跑一遍
  （合法写法不得误报、编造端点/参数/类型/注释分隔写法/同名顶替必须被拦下），自检失败即编译失败 ——
  这是为了避免「门禁退化成一个永远 PASS 的摆设」。
- **约定**：Retrofit 接口方法请保持标准形态（`@GET("path")` + 具体契约类型签名 + `@Body`/`@Query`/`@Path`
  注解参数），别用注解常量拼接、动态 URL 或本地同名类型顶替；那会被门禁判为「无法核对」而拦住。
- 快照刷新后忘记重新生成 → `contract-codegen` 红；契约里没有的端点/参数/类型 → 编译红。
  两者都不是「跳过」，是必须修的真问题。

## 为什么自己写生成器（而不是 openapi-generator / kotlinpoet）

评估过两条路：

1. **openapi-generator 的 kotlin 生成器**：功能覆盖最全，但要在 CI 里跑就得拉 jar（或把二进制提交进仓），
   结果受生成器版本影响；默认还会产出 `apis/`、`infrastructure/` 等与这个客户端无关的一整套代码，
   且本仓契约有 566 个 schema，全量生成会让编译时间与噪音失控，仍需要额外做「只生成闭包」的裁剪。
2. **本仓自带的 python3 生成器**（现状）：只依赖标准库，离线可跑；输出只取决于契约字节
   （无时间戳、无随机顺序、属性与类型都排序），因此可以逐字节比对；只生成
   `client-operations.json` 里 33 个 operation 的闭包（99 个类型）；规则简单到能逐条写进本文档并逐字段核对。

选 2：可复现性与可审计性优先于功能覆盖 —— 这个生成器的目标不是「支持任意 OpenAPI」，
而是把这 33 个 operation 的边界钉死；真需要支持更复杂的契约形态时，再评估引入 openapi-generator。

## 注意

- 接口边界（`ApiService.kt` + 它用到的 DTO）现在是**契约生成 + 编译期门禁**：
  字段名/类型写错编译不过，契约里没有的端点/参数由 `verifyContractEndpoints` 在编译期拦下。
  改接口相关代码前仍然先对照本文件 —— 门禁只保证「与契约一致」，不保证「这个接口就是你要的业务语义」。
- 契约新鲜度只证明**快照字节**与上游一致；生成物一致性只证明**生成物**与快照一致。
  两者都不证明**业务调用点**用对了字段语义。
- 目前仍为手写、且**不在**生成范围内的：`MobileSyncState` 等状态模型、DAO 实体、以及各 Repository
  的内部模型（工单口径：只锁接口边界这一层）。

## 手写模型一致性报告（历史基线）

> 下面两份报告记录的是**改造前**手写模型与契约的静态差异核对（2026-10-12，刷新快照时同步产出），
> 现状已改为契约生成。报告保留作为「当时到底差在哪」的证据；其中列出的偏差多数已由生成模型消除，
> 少数仍成立的行为结论（如 `readOnly` 字段不该出现在请求里、整数枚举的形状冲突）在 PR 里被显式复核。

刷新快照时同步核对了本仓手写模型与契约的静态差异，**只报告、不修产品**：

- [`model-consistency.md`](model-consistency.md) —— 人读报告（逐 operation 判定、security、覆盖口径、未验证项）
- [`model-consistency.detail.json`](model-consistency.detail.json) —— 机读详表（每条 finding 带
  `contractField.{required,readOnly,writeOnly,nullable,default,enum,ref,type}` 与 `requiredSemantics`，
  另有 `requestBodyAudit` / `responseContainerAudit` / `queryParamAudit` / `security` / `coverage`）

注意：`required` 不区分方向；契约 schema 里 `readOnly: true` 且 `required` 的属性按 OpenAPI 3.0
**只对响应生效**，客户端不发它可能是正确行为，不能据此判定客户端 bug。
