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

> 本仓**没有** `generated.ts`、**没有** openapi-typescript 生成步骤 —— 客户端模型是手写的，
> 所以本设施刷新/校验的产物只有**两个**：`contract/openapi.json` 与 `contract/provenance.json`，
> 不安装 npm、不生成代码。

## 怎么刷新

后端契约更新后（用户告知，或你发现契约里缺了已有字段）：

```bash
scripts/ci/fetch-contract.sh
```

脚本会把**两个**产物先写在仓外临时目录、全部校验通过后才安装进仓：
`contract/openapi.json`（上游原始字节）与 `contract/provenance.json`（来源记录）。
**刷新后请一并提交这两个文件**，并在 PR 里说明刷新原因。
若解析 ref / 取源 / payload 形状任一失败，脚本**一个字节都不写**，原有产物保持原样。

只读校验（CI 用的就是它，绝不写仓）：

```bash
scripts/ci/check-contract-freshness.sh
```

行为测试（离线 fixture 上游，证明一致=绿 / 陈旧=红 / 取不到源=红 / 旁路被拒）：

```bash
scripts/ci/test-contract-freshness.sh
```

「谁写」= `fetch-contract.sh`（人工 refresh）；「谁只读」= `check-contract-freshness.sh`（CI）。

## 注意

- 手写模型对不上契约时，编译/构建不会报错，问题只会在运行时出现。改接口相关代码前，先对照本文件。
- 契约新鲜度只证明**快照字节**与上游一致，**不证明**手写模型符合契约 —— 后者见下面的报告。

## 手写模型一致性报告

刷新快照时同步核对了本仓手写模型与契约的静态差异，**只报告、不修产品**：

- [`model-consistency.md`](model-consistency.md) —— 人读报告（逐 operation 判定、security、覆盖口径、未验证项）
- [`model-consistency.detail.json`](model-consistency.detail.json) —— 机读详表（每条 finding 带
  `contractField.{required,readOnly,writeOnly,nullable,default,enum,ref,type}` 与 `requiredSemantics`，
  另有 `requestBodyAudit` / `responseContainerAudit` / `queryParamAudit` / `security` / `coverage`）

注意：`required` 不区分方向；契约 schema 里 `readOnly: true` 且 `required` 的属性按 OpenAPI 3.0
**只对响应生效**，客户端不发它可能是正确行为，不能据此判定客户端 bug。
