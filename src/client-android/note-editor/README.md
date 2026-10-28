# PIM offline Markdown editor

This is a locally built Tiptap/ProseMirror editing surface inside the native
Android notes screen, not a remotely loaded WebUI. Recording, notifications,
photos, vector ink, persistence and fixed material versions remain native.
No OPPO application code, private bridge, player or model is included.

## Build and tests

Requires Node.js >= 20.19 and npm. Versions and integrity hashes are locked in
`package-lock.json`. From this directory:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run verify
```

Android `preBuild` installs, tests and bundles this module automatically. A
cross-process file lock protects the two concurrent Gradle invocations in CI;
verified input/output hashes avoid rebuilding the same assets twice. No CI
workflow changes or pre-generated JavaScript are required.

Output is under `app/build/generated/note-editor-assets/note-editor/` and is
packaged in the APK. `THIRD-PARTY-NOTICES.txt` contains versions and complete
licenses for all bundled runtime dependencies. npm dependencies and generated
assets are not source-controlled. `NOTE_EDITOR_OUT` can select a test output
directory.

## Native boundary and source preservation

- Existing schema-v1 documents, block IDs/order, photos, vector ink and frozen
  input versions remain unchanged. Merely loading, previewing, focusing or
  changing the theme does not normalize the original Markdown.
- User edits serialize the supported Markdown subset. HTML, Markdown images,
  reference definitions, footnotes, front matter and blocks over 500,000
  characters use the source-preserving native editor, with an explanation.
  Unsupported or oversized paste is refused visibly; use source mode to paste
  it without conversion. Clipboard HTML and files are never silently imported.
- A material insertion carries exact expected source and independently valid
  Markdown fragments on both sides of the caret. It is applied only after the
  native operation succeeds. Cancelled photography does not rewrite source;
  stale anchors fail rather than inserting at an unrelated location. Camera
  restoration stores large anchors in private atomic files, not Activity state.
- Bridge messages require the main frame, exact asset origin/page, protocol,
  random per-view session, native epoch and nondecreasing sequence. The native
  repository is read synchronously to avoid stale Compose echoes. Native
  document replacements advance the epoch and reject late old messages.
- Native navigation, finish, export, preview and material insertion flush live
  editors and wait for IME composition, with a bounded failure. Composition is
  not displayed as saved. Backgrounding and virtualization request a final
  snapshot; renderer failures retain the latest received source and visibly
  fall back. This cannot promise recovery of characters never received from a
  crashed renderer or OS-killed process.
- A per-note coroutine gate owns the whole commit/action/resume transaction,
  including backgrounding. Live leaves remain pinned during that transaction.
  Each physical view has its own pending state and final capture; retiring
  views stay registered until acknowledgement. A replacement for the same
  block cannot initialize or accept input until its predecessor finishes.
  Concurrent callers share a capture, and a destroyed, confirmed predecessor
  is not mistaken for an unavailable live editor. Native rebinding cannot
  disable the DOM or change its epoch mid-commit.
- Failed/unconfirmed input opens the native source editor with an explicit
  review action. The header says “待核对正文” and locates the affected block.
  Review survives Activity recreation; merely editing source does not silently
  acknowledge it. Only block IDs are saved for recovery state, not a second
  large copy of source. A known format fallback needs no failure acknowledgement.
  Renderer termination follows the [Android WebView lifecycle](https://developer.android.com/develop/ui/views/layout/webapps/handle-termination);
  recording remains independent. The recovery tests use a real renderer kill
  with controlled bridge delay, user-page scrolling, and a separate real-view
  handoff with delayed capture. These are not OPPO or acoustic acceptance.
- Focused caret bounds are mapped into the native scrolling document. IME and
  editor-height changes bring that range above the native format bar/dock.
  The focused leaf is pinned through lazy remeasurement; caret scrolling also
  reacts to the measured native viewport and actual leaf position, after its
  new layout frame. The native list applies the actual missing caret distance;
  whole-WebView rectangle requests are suppressed to avoid competing scrolls.
  Reading leaf position does not itself recompose or restart scrolling.
  Actual placement changes request a coalesced UI-queue reveal using the latest
  geometry; a frame-clock callback alone does not mean placement has finished.
  A range taller than the available viewport aligns its lower edge without
  alternating between two impossible bounds.
  The host coalesces a fresh selection snapshot after the
  browser layout frame, using the current native epoch rather than a queued
  old source. This avoids losing selection when the IME/toolbar reduces a
  compact viewport,
  rather than relying on WebView scrolling to understand a Compose viewport.
- Only index.html, editor.js and editor.css are accessible to the WebView.
  Other assets, private files, content URIs, remote requests, navigation and
  frames are blocked; CSP also denies network/image/frame access. There is no
  `addJavascriptInterface`. Account tokens and arbitrary attachment paths are
  never sent to JavaScript. Theme values come from native semantic resources;
  font scale is applied once.

## Deliberate MVP limitations

The mature engine edits individual Markdown leaf blocks. Photos/ink remain
native blocks between them. Undo/redo is per live text leaf, not global across
material insertion/deletion, source-mode changes or view recreation. A very
long leaf caps the outer WebView height at 8192 CSS pixels and then scrolls
inside it. A unified whole-document engine, many-block performance profiling,
OPPO IME/stylus/palm rejection and 100/180-minute recording acceptance remain
explicit gaps, not implied by automated tests.

No font or animation from the reference archive is shipped in this slice;
their user-confirmed availability remains recorded for later selection. This
slice does not add upload, server ASR, complete-record generation, independent
analysis, multi-device conflicts, deletion/retention or Windows/Web recording.
It is not the complete MVP. The sole capability/gap tracker remains
`pim-api/docs/product/native-notes-roadmap.md` (NOTE/INK/REC/AI/SYNC/SAFE and
migration IDs); do not create a competing roadmap here.
