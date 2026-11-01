# Android M5 MIG01-13 native action inventory

This inventory records the existing Android implementation, the actions that were
missing, what this wave added, and the honest Web-only boundary that remains. A
row is not considered migrated merely because it can render data or open a
WebView.

| MIG | Existing native capability | Missing actions before this wave | Added in this wave | Remaining / boundary |
|---|---|---|---|---|
| MIG01 Today | Native queue/status bar, sync submit, embedded `/embed/android/today` | Native task/event actions and a native business summary | Native workbench opens on Today, shows today's usage summary, tasks/events, and can create/edit/complete tasks and create events through contract APIs | The existing embedded Today route remains for Web-only summaries; continuous collection and sync status were intentionally left unchanged |
| MIG02 Workbench | No native task workbench; `/tasks` is WebView-only | Cross-domain native action entry | Native multi-section workbench (Today, Tasks, Calendar, Reminders, Devices, Knowledge, Diagnostics, Quick Notes) with real load/refresh/action feedback and explicit partial/error states | AI plan generation and advanced calendar layer composition remain Web-only |
| MIG03 Calendar | Native read-only schedule policy and mute refresh | Create/edit/delete with confirmation; create a writable calendar | Native calendar creation, a writable-calendar picker, event create/edit dialogs and explicit delete confirmation using `calendar/events`; event ranges are bounded to the next seven days | Visual month/week grid, recurrence scope editor, drag scheduling and Outlook direct write-back remain Web-only; Outlook mirrors and recurrence-bearing records are explicitly blocked from direct native mutation |
| MIG04 Quick HTML compatibility | No native quick-note module | List/detail/create/edit/archive/process/restore/delete while preserving old HTML/attachments/identity | Independent `nativework.quicknotes.LegacyQuickNoteRepository`; update requests retain every existing attachment id and never send/invent `metadataJson` | No attachment upload/delete because the client contract does not contain that operation; old HTML/metadata stays server-authoritative and is not overwritten |
| MIG05 Tasks | Contract methods existed, but `/tasks` UI was WebView-only | Native list/search/create/edit/complete/reopen/delete/timer; task-book list/create/assignment | Paged task load (100-row bound), task-book list/create and assignment in the editor, replacement-safe edit, completion toggle, timer start/pause/stop, and delete confirmation | Checklist, batch update/delete, drag planning and full multi-page navigation remain Web-only |
| MIG06 Confirmations | No native confirmation center | Accept/reject/detail actions | Delete confirmations for tasks, events, devices, categories and quick notes; server delete-preview for devices | General operations-confirmation inbox and audit timeline remain Web-only |
| MIG07 Mobile records | Native usage/forensic capture and local status surfaces | Native server mobile-record browsing | Server system/diagnostic status is available from the workbench; existing local queue/status screens remain authoritative | Full server timeline/usage event paging and per-record detail remain Web-only |
| MIG08 Location history | Native location, tracks, passive/sprint collection and queue status | Native analytics/history actions | No location policy/source/queue/write-path changes in this slice | Location history/track WebView stays untouched so M5 cannot regress the separately owned location roadmap |
| MIG09 Devices | Device registration and local identity | List/rename/delete-preview/delete | Native managed-device list with active/data sort, safe rename, server-provided delete impact, and confirmed deletion | Device detail, export, and merge endpoints are not in the current client operation set and remain Web-only |
| MIG10 Knowledge/classification | No native category/app-context UI | Category tree read/save/delete; app/context read; context save/delete | Native flattened category tree with root/child create, edit, guarded delete; app search, context list, add and delete | App-signature create/edit/delete and suggestion apply are absent from the contract used here and remain Web-only; no guessed endpoints were added |
| MIG11 Diagnostics/status | Native status center, bounded diagnostic export, crash forensics and log/history data | Server-side status detail action in the native workbench | Native workbench reads `/status` and renders summary, each component's raw status/details/timestamp and next steps; links to the existing native status/export screen | No OS-level device/ADB/AVD verification was performed in this environment |
| MIG12 Settings | Full native settings, server/session mutation, permissions, collection policy and reset flows | Preserve and route from the new action surface | Workbench is reachable from native More navigation; status and settings actions route to the existing native Status and Settings screens rather than a WebView | AI/admin/MCP/advanced Outlook configuration screens remain Web-only |
| MIG13 Data governance and adjacent business | Native diagnostics/history plus existing Web entry points for reports, reminders, habits, files/data center | Native reminder actions and honest governance boundaries | Native reminder list with server-backed dismiss and validated future-time snooze; no unsupported CRUD was fabricated and existing collection is untouched | Reports, habits, files, recycle bin, audit restore, data center and endpoint-shell actions remain Web-only pending contract-backed implementations |

## Contract policy

- Every new call uses an operation already present in `contract/openapi.json`.
- `scripts/contract/client-operations.json` was extended and
  `ContractModels.kt` was regenerated by the generator; the generated model file
  is not hand-edited.
- Mutable Task/Event requests preserve fields not shown in the compact editor.
- Quick-note metadata cannot be sent by the contract update shape, so the native
  editor preserves attachment ids and leaves metadata server-authoritative.
- Outlook mirror events are shown but direct mutation is blocked; Outlook uses
  its dedicated sync/write-back contract.

## Verification boundary

The M5 source phase uses JVM tests and contract generation checks. No physical
device, ADB, AVD, production database, private data, or credentials are used.
Heavy Gradle verification requires the shared build slot and is reported
separately from source-level checks.
