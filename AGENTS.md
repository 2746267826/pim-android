# Repository Git Hygiene

This repository keeps `master` at the latest runnable version of the Android collection client. The rules below constrain what may enter `master`; they do not prescribe how you organize your work to get there.

## 接口契约与门禁

### 与后端的接口

- 本仓与 `pim-api` 的接口以 `contract/openapi.json` 为准（快照随本仓提交，说明见 `contract/README.md`）。
- **不要凭猜测调用接口**：不得手写或臆测契约里不存在的路径、字段、枚举值。改动涉及接口时，先对照契约确认形状。
- **不得伪造客户端接口来补齐后端缺失能力**：新接口必须在 `pim-api` 实现并更新契约后，客户端才能刷新快照并接入；这不要求由用户转交需求或采用特定跨仓协作方式。
- 后端契约更新后，用 `scripts/ci/fetch-contract.sh` 刷新本仓快照（`contract/openapi.json` + `contract/provenance.json`）再提交。
- **快照落后会让本仓 CI 变红**：`CI` 的 `Contract freshness` job 会把快照与上游按解析出的不可变 commit 做逐字节比较，并将该 job 接进 `CI Gate` 与 `release` 的 `needs`。刷新脚本是唯一写入口；`scripts/ci/check-contract-freshness.sh` 只读、不写仓，CI 调的是它。细节与来源记录见 `contract/README.md`。

### 门禁

- 合并的必需锁是 **`CI Gate`**：逐个 check `resolve-version` / `build-android` / `build-shell-android` / `contract-freshness` / `contract-codegen`，任一失败或 skipped 即 Gate 红；`release` 也依赖这五个 job，契约陈旧或生成物对不上时不发版。
- 不要绕过门禁：不把失败/取消/意外 skipped 的必需 check 当作通过，不改 ruleset 或 `.github/workflows/*` 来让门禁变绿，不使用 admin bypass。（原来的 PIM Independent Acceptance App 已卸载，没有第二把锁，也不要引入替代的人工盖章流程。）

### 本仓特有的一条：接口边界是「契约生成 + 编译期门禁」，不是手写

- 与后端交互的 DTO 由 `contract/openapi.json` **生成**，落在 `src/client-android/core/src/main/java/com/pim/core/contract/ContractModels.kt`（包 `com.pim.core.contract`）。文件头写着「本文件由工具生成，请勿手工修改」——**不要手改**，改了 CI 的 `Contract codegen freshness` 会红。
- 生成/校验入口：
  - `scripts/contract/client-operations.json` —— 客户端用到的 operation 清单（METHOD + 契约路径）；
  - `scripts/contract/generate-kotlin.py` —— 生成器（只用 python3 标准库，离线、确定性）；
  - `scripts/ci/generate-contract-kotlin.sh` —— 重新生成（唯一写入口）；
  - `scripts/ci/check-contract-kotlin.sh` —— CI 用的只读校验（重新生成到仓外临时目录后逐字节比对）；
  - `scripts/ci/test-contract-kotlin.sh` —— 该门禁的行为测试（一致=绿 / 手工改动=红 / 契约变了没重生成=红 …）。
- **编译期拦截**：`ApiService.kt` 的端点、`@Path`、`@Query`、`@Body` 由 gradle 任务 `verifyContractEndpoints`（`src/client-android/build.gradle.kts`，挂在各模块编译任务之前）对照契约逐个校验——契约里不存在的端点/参数会**编译失败**；字段名/类型写错则本来就编译不过（DTO 是生成的）。
- `ApiService.kt` 与调用点必须匹配仓内契约和生成类型；新增接口需要对应的后端实现与契约，不得在客户端伪造。

## Communication

- Communicate with the user in Simplified Chinese by default.
- For user-facing UI, default visible text to Simplified Chinese. Keep code identifiers, API names, logs, protocol fields, and third-party product names in English unless localization is explicitly part of the task.

## Branch, Commits, And Pull Requests

- All file-changing work must happen on a non-`master` branch, delivered as a pull request. Do not push directly to `master` unless the user explicitly asks for a direct update and understands it bypasses the PR workflow.
- Branches use the `{agent}-{os}/{topic}` prefix (e.g. `codex-win/agent-decouple`, `xiaoi-linux/location-fix`). `agent` is the AI agent's name, `os` is `win` or `linux`, and `topic` is a short kebab-case English summary of the branch's purpose. Branches opened automatically by tooling (e.g. `task/<n>`) do not follow this scheme.
- Use focused commits with conventional messages such as `feat:`, `fix:`, `docs:`, or `chore:`. Write both commit messages (titles & descriptions) and PR titles/descriptions in bilingual format (English and Simplified Chinese).
- Work wherever it is convenient (main checkout, a worktree, another machine); what matters is that `master` stays clean and mergeable, and that you do not revert or overwrite changes you did not create.
- Do not modify `.github/workflows/*` unless the task is specifically about CI/release automation or the user explicitly asks for it. If a workflow change is unavoidable, explain why before editing it.
- After opening or updating a PR, confirm the required GitHub Actions checks pass before calling the task complete. If no workflow triggered because of path filters, say so explicitly. Report failures honestly; never describe a failing, cancelled, or unexpectedly skipped required check as green.

### Pull Request Format

- Every PR description MUST include the four bilingual sections below, keeping the exact heading format, plus the requirement & acceptance mapping table from the PR template. CI extracts the four sections into the GitHub Release changelog (`scripts/ci/build-release-notes.sh`):
  - `## 技术修改 / Technical changes` — modules/files touched, key design decisions, API or schema changes, dependency changes
  - `## 功能变化 / Feature changes` — user-visible changes; write `无 / None` if none
  - `## 如何体验 / How to try it` — how a user experiences the feature: which screen, what actions, what visible effect to expect (step-by-step is fine). This is an experience guide for users, NOT deployment/build instructions — build/run/verify commands belong in 测试 / Tests
  - `## 测试 / Tests` — verification commands run and their results
- In the mapping table, results may only be `PASS` / `FAIL` / `BLOCKED` / `NOT-VERIFIED` / `APPROVED-EXCEPTION`, backed by what was actually executed. Requirements and acceptance criteria can be written directly in the PR; no separate work order is required. With an external ticket, keep its numbering as-is; maintenance changes without a ticket may write `无 / None`, but the 测试 / Tests section must still contain real execution results.

## Verification Commands

- The commands below are optional local verification help, not a required pre-PR procedure. Existing CI owns the required checks. Base verification claims on actual output and identify the tested revision and environment; do not present stale or assumed results as current evidence.
- App unit tests: `src/client-android/gradlew :app:testDebugUnitTest` (Windows: `src/client-android\gradlew.bat :app:testDebugUnitTest`).
- Connected status UI verification uses a started emulator or physical device: `src/client-android/gradlew :app:connectedDebugAndroidTest --no-daemon`. CI provides no emulator, so connected behavior cannot be verified in CI — if you cannot run it, record it as `NOT-VERIFIED` instead of guessing.
- Report failed or missing verification explicitly. Do not claim acceptance from test code that was not executed or from unrelated passing checks.

## During Work

- Keep generated outputs out of commits: `src/client-android/build/`, `src/client-android/**/build/`, `.gradle/`, `*.apk`, `*.aab`, `local.properties`.
- Commit source changes, tests, scripts, and docs that are needed to reproduce the current runnable version.
- Keep API and daemon defaults aligned. The local API is expected at `http://127.0.0.1:5858`, and the Windows daemon default server URL should match it.

## Production Database And Logs

- Production runs on the home Docker host: the API in the `pim-pim-1` container, the database `pim_prod` in the 1Panel PostgreSQL container (`127.0.0.1:5432`).
- **Never point an agent session at `pim_prod`.** Work that needs real data uses the mirrored database `pim_test`, restored from a `pim_prod` snapshot (`pg_dump -Fc` → `pg_restore`) with credentials and personal tokens sanitized. The mirror is disposable and safe to mutate; its contents are "as of the snapshot time", so never write results back to production.
- Production logs are JSONL files inside the running container — `/data/pim/logs/pim-api-<YYYYMMDD>[_NNN].jsonl` (Serilog compact JSON; `@t` is UTC, and files roll at ~1 GB so the newest data may live in a `_NNN` file). Read them with `docker exec pim-pim-1 tail` / `grep`; `docker logs` only carries startup and crash output.
- Never copy production dumps, log extracts, or credentials into a PR, an issue, or any artifact.
- Tokens belong in memory or encrypted storage only, never in WebView or uploads. Logs and confirmation pages must not leak tokens, GraphEventId, ChangeKey, or other private identifiers. URL allowlist validation must account for redirects and URLs returned by upstream services.
