# Implementation Plan: WCode jDesk Migration

## Overview

Thực hiện theo vertical slices, giữ JavaFX làm rollback path cho đến khi jDesk đạt feature
parity. Thứ tự ưu tiên là: chứng minh build/runtime + dữ liệu hiện có, sau đó read-only WB flow,
rồi các mutation/local workflows, cuối cùng packaging/cutover.

## Dependency Graph

```text
jDesk/Gradle foundation
  ├─ typed command + capability policy
  │    ├─ shop/bootstrap DTO (secret-safe)
  │    │    └─ dashboard local
  │    │         └─ WB live sync
  │    │              ├─ supply/order browsing
  │    │              │    └─ packing + print
  │    │              └─ FBO browsing + print
  │    ├─ KIZ/GTIN mapping
  │    │    └─ Znack purchase/introduction/signing
  │    └─ license/update/settings
  └─ React/Tailwind shell + design system
       └─ every feature screen + accessibility/native E2E

Feature parity + early Windows native gates
  └─ data/secret migration
       └─ canary cutover + rollback window
            └─ remove JavaFX
```

## Architecture Decisions

- Parallel replacement + canary cutover trong repo hiện tại; xem
  `docs/decisions/ADR-001-jdesk-parallel-replacement.md`.
- Java giữ business logic; React chỉ presentation/orchestration.
- Generated TypeScript bindings là contract source-of-truth.
- Command capability nhỏ theo feature; `main` không nhận generic filesystem/secret/shell access.
- Remote WB/Znack và browser content luôn là untrusted data.
- Live test mặc định read-only với seller-state; local SQLite writes từ sync được phép và backup được.

## Phase 0: Evidence and baseline

### Task 0.1: Record framework research and migration contract

**Acceptance criteria:**

- [x] Official jDesk docs, limitations and public registry state documented.
- [x] Spec covers objective, commands, structure, style, tests, boundaries and success criteria.
- [x] ADR records incremental migration and alternatives.

**Verification:** Review links and run public scaffold probe.
**Files:** `research.md`, `spec.md`, `ADR-001-jdesk-parallel-replacement.md`, `plan.md`.
**Dependencies:** None.
**Scope:** M.

### Task 0.2: Capture legacy baseline

**Acceptance criteria:**

- [x] `./mvnw -B verify` passes on current branch.
- [x] Existing data counts and shop count are recorded without secret values.
- [x] JavaFX-dependent source inventory is committed to parity matrix.

**Verification:** Maven output + read-only SQLite queries.
**Files:** `docs/jdesk-migration/parity-matrix.md`, optionally baseline evidence text.
**Dependencies:** Task 0.1.
**Scope:** S.

## Phase 1: Runnable foundation

### Task 1.1: Add reproducible jDesk Gradle build

**Acceptance criteria:**

- [x] Gradle 9.6.1 wrapper and jDesk 0.1.3 resolve only from public registries.
- [x] Core sources compile while JavaFX source is explicitly excluded.
- [x] Shared dependency versions/resource filtering match the Maven build.
- [x] `jdeskDoctor` and empty app classes pass without changing Maven build.

**Verification:** `./gradlew clean classes jdeskDoctor`; `./mvnw -B verify`.
**Files:** `settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper generated files, `.gitignore`.
**Dependencies:** Task 0.2.
**Scope:** M (generated wrapper excluded from logical-file count).

### Task 1.1a: Enforce single-writer app-data ownership

**Acceptance criteria:**

- [x] JavaFX and jDesk request the same app-data lock before database initialization.
- [x] A second process fails safely without touching SQLite; tests use isolated app-data paths.
- [x] Lock is released on orderly exit and OS process termination.

**Verification:** JUnit contention/release tests and two-process smoke.
**Files:** shared lock service, JavaFX launcher integration, jDesk main integration, test (max 4).
**Dependencies:** Task 1.1.
**Scope:** M.

### Task 1.1b: Add migration-safe SQLite snapshots

**Acceptance criteria:**

- [x] Snapshot dùng SQLite backup API dưới shared lock, bao gồm committed WAL state, rồi verify
  checksum trước khi migration/writer version tiếp tục.
- [ ] Snapshot được tạo trước first launch, mỗi schema-changing migration và mỗi canary writer
  version; retention giữ rollback points còn hiệu lực.
- [x] Restore cần xác nhận và tạo snapshot/export của database mới hơn trước khi thay thế.
- [ ] Transaction/version marker fail-closed; recovery CLI ngoài normal DB bootstrap có thể
  list/verify/restore khi cả hai UI không khởi động.

**Verification:** JUnit WAL/snapshot/corruption tests trên app-data temp + restore rehearsal.
**Files:** snapshot service, metadata record, migration hook, test (max 4).
**Dependencies:** Task 1.1a.
**Scope:** M.

### Task 1.2: Define bootstrap/dashboard contract with RED tests

**Acceptance criteria:**

- [x] Test fails for missing command service, then passes after implementation task.
- [x] Bootstrap returns app metadata, sanitized shops and selected shop id.
- [x] Dashboard command validates shop id and returns local KPIs without API key.

**Verification:** `./gradlew test --tests '*WorkspaceCommandServiceTest'`.
**Files:** `src/jdeskTest/.../WorkspaceCommandServiceTest.java`, test fake/fixture if required.
**Dependencies:** Task 1.1b.
**Scope:** S.

### Task 1.3: Implement composition root and command adapter

**Acceptance criteria:**

- [x] Database initializes before first command.
- [x] `workspace.bootstrap` and `dashboard.load` use explicit capabilities.
- [x] Central command error boundary converts expected/unexpected failures to safe allowlisted
  envelopes; fault-injection never exposes raw throwable/upstream/SQL text.
- [x] Generated Java registry and TS binding compile.
- [x] `--jdesk-smoke` starts and stops cleanly.

**Verification:** RED test becomes green; `./gradlew bindings classes`.
**Files:** `src/jdesk/java/.../Main.java`, `WorkspaceCommandService.java`, safe error mapper,
`src/jdesk/resources/jdesk-capabilities.json` (split as two increments if over 5 files).
**Dependencies:** Task 1.2.
**Scope:** M.

### Task 1.4: Scaffold React/TypeScript/Tailwind toolchain

**Acceptance criteria:**

- [x] React 19, Vite 7, Tailwind 4 and jdesk-client are lockfile-pinned.
- [x] strict typecheck, lint, Vitest and production build scripts exist.
- [x] CSP-compatible build contains no inline/eval requirement.
- [ ] Production window loads bundled content only; external navigation is blocked or handed to the
  system browser without bridge capabilities.

**Verification:** `npm ci --prefix ui`; lint, typecheck, test, build; `npm audit`.
**Files:** `ui/package.json`, `ui/package-lock.json`, `ui/vite.config.ts`, `ui/tsconfig.json`,
`ui/index.html`.
**Dependencies:** Task 1.1.
**Scope:** M.

### Task 1.5: Build design tokens and accessible shell

**Acceptance criteria:**

- [x] Responsive application shell with sidebar/compact navigation and visible focus.
- [x] Semantic color/type/spacing tokens support light/dark/system.
- [x] Real WCode navigation labels, no placeholder copy or generic AI visual style.

**Verification:** component tests + 320/768/1024/1440 screenshots.
**Files:** `ui/src/styles.css`, `ui/src/App.tsx`, `ui/src/components/AppShell.tsx`, shell test.
**Dependencies:** Task 1.4.
**Scope:** M.

### Task 1.6: Connect shop picker and local dashboard

**Acceptance criteria:**

- [x] UI loads existing shops through generated command, selects saved/default shop.
- [x] Dashboard handles loading, success, empty and error states.
- [x] No API key exists in DOM, command response, console or frontend types.

**Verification:** Vitest behavior tests + jDesk automation against temp and live DB.
**Files:** feature components/hooks/tests, generated bindings only as build output.
**Dependencies:** Tasks 1.3 and 1.5.
**Scope:** M; split presentation and integration into separate increments if over 5 files.

### Task 1.7: Run early Windows feasibility gates

**Acceptance criteria:**

- [ ] WebView2 app starts and automation console/snapshot work on Windows x64.
- [ ] File open/save dialog, existing PDF open/print path and CryptoPro discovery are probed.
- [ ] JPMS/native-access spike either produces a modular composition root or an explicit blocked
  report before any mutation/external beta work.

**Verification:** Windows CI artifacts plus real-machine evidence for interactive/physical paths.
**Files:** probes/tests/evidence docs, no product behavior change (max 5 logical files).
**Dependencies:** Tasks 1.3 and 1.4.
**Scope:** M.

### Checkpoint A: Foundation

- [x] Legacy Maven verify passes.
- [x] jDesk Java/frontend gates pass.
- [x] Native app shows existing shop/dashboard data with clean console.
- [x] No secret is returned by Java, rendered in DOM after submit or written to console/logs.
- [x] Shared live database cannot be opened by both entry points concurrently.
- [x] Verified SQLite/WAL snapshot exists for the current writer/schema version.
- [ ] Fault-injection and external-navigation tests prove exception/capability containment.

## Phase 2: Wildberries read synchronization

### Task 2.1: Contract and tests for safe WB sync

**Acceptance criteria:** typed result reports product/supply/order counts and structured safe
errors; duplicate clicks coalesce/disable; cancellation is defined.
**Verification:** failing JUnit tests first.
**Files:** command test and fixture files (max 3).
**Dependencies:** Checkpoint A.

### Task 2.2: Implement `wildberries.syncOverview` and progress events

**Acceptance criteria:** uses selected shop secret only in Java; emits bounded/coalesced progress;
does not mutate seller-state; refreshes local dashboard after completion.
**Verification:** JUnit integration + read-only live shop smoke.
**Files:** WB command adapter, event DTO, capabilities, integration test (max 4).
**Dependencies:** Task 2.1.

### Task 2.3: Add professional sync UX

**Acceptance criteria:** button/loading/progress/retry, token-expired message, last-updated status,
and accessible announcements work.
**Verification:** Vitest + native automation network/console inspection.
**Files:** dashboard hook/component/test files (max 5).
**Dependencies:** Task 2.2.

## Phase 3: Shop and credential management

Tasks are split into: typed CRUD contract; write-only token dialog/form; OS secret-store
write-through; verified dual-read/write + rollback migration; plaintext-column retirement.
Database deletion requires explicit approval and only happens after all shops read back from OS
storage on Windows target **and** the first-cutover JavaFX rollback window has expired.

Trong dual-write phase, legacy credential là source-of-truth. Save ghi legacy + monotonic version
trước, OS store sau; fingerprint/version mismatch được reconcile về OS store mà không expose token.
Tests bắt buộc cover crash/failure sau từng write và chứng minh JavaFX rollback vẫn dùng token mới.

### Checkpoint B: Shop/WB

- [ ] Existing shops can be viewed, created, edited and deleted with confirmation.
- [ ] API key is masked and never returned after save.
- [ ] Live WB sync passes for a user-selected shop.
- [ ] Legacy JavaFX still reads data during rollback window.

## Phase 4: Supply and order workspace

Vertical slices, each with contract → RED test → Java adapter → React UI → native E2E:

- [x] Paginated supply list with search/status/filter.
- [x] Supply detail and paginated order table with stable sorting/search.
- [x] Cached product-image preview via asset route, never remote URL or oversized JSON.
- [x] Refresh orders/status and restore selection.
- [x] Excel order import through a native open dialog, bounded parser, opaque paged session and
      live sticker lookup.
- [x] Sticker lookup and PDF save/open are handled as one bounded print transaction in
      Phase 5. The legacy workflow does not provide a separate Excel-export feature.

### Checkpoint C: FBS read workflow

Operator can select a real shop/supply, inspect all orders, filter/sort, refresh from WB and
recover from rate limit/offline/error without JavaFX.

## Phase 5: Printing, packing and history

- [x] Port print option contract and template list.
- [x] Port PDF generation command and native save/open workflow, including KIZ preflight and
      safe background-attachment handoff.
- [ ] Port packing workflow and its operator-facing KIZ availability states.
- [ ] Port print history/reprint.
- [ ] Port template designer with unit conversion and visual preview.
- [ ] Validate physical 58×40 output and Windows printer behavior.

Windows print gaps in jDesk 0.1.3 are a hard release risk. Preserve the existing Java PDF/OS
path or add a verified Windows adapter; do not claim parity from macOS/browser preview.

## Phase 6: FBO

Port subject/search pagination, barcode plan, single/batch print and templates as independent
vertical slices. Verify performance with the existing 17k+ product dataset.

## Phase 7: KIZ and Znack

1. GTIN mapping inventory/read/edit.
2. KIZ import with file type/size/magic-byte validation.
3. Purchase pipeline and progress events with idempotency/recovery.
4. CryptoPro certificate discovery/signing on Windows.
5. Introduction status/retry and audit-safe errors.

Any real KIZ purchase/introduction mutation requires explicit approval and a dedicated shop/test
artifact. Recovery/idempotency tests precede implementation changes.

## Phase 8: License, update, settings and diagnostics

Port license activation/status, i18n, theme, update check/install and support bundle. Update and
installer tasks must include signature/checksum validation and rollback evidence.

## Phase 9: Production hardening and cutover

1. Full parity audit against every JavaFX controller/FXML action.
2. WCAG/accessibility, WebView cross-platform, performance and dependency audits.
3. Confirm the early JPMS/native-access decision and framework 0.1.3 upgrade/freeze review.
4. Windows/macOS/Linux native app/installer CI, signing and clean upgrade tests.
5. Verified per-migration SQLite/WAL snapshots, single-writer ownership and both rollback-path
   rehearsals.
6. Canary jDesk installer theo internal → 10% → 50% → 100%; mỗi cohort giữ tối thiểu 7 ngày,
   20 cold launches trên 2 máy Windows, exercise mọi journey và zero critical/high hoặc
   data/credential/secret incident. Default entry point chỉ đổi sau 100% parity evidence.
7. Remove JavaFX/FXML/Maven packaging only after zero uncovered parity items and rollback window.
8. Update README, operations runbook, release notes and deprecation notice.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| jDesk pre-alpha breaking change | High | Pin 0.1.3, adapter boundary, upgrade task only |
| Big controller hides business behavior | High | Inventory every event handler; parity matrix + legacy oracle |
| WB/Znack accidental mutation | High | Read-only default, explicit approval, capability separation |
| API key exposure through WebView | High | Secret-safe DTO, response/DOM/console tests, OS store migration |
| Windows printing/CryptoPro mismatch | High | Real Windows gates, preserve Java implementation until proven |
| Dual build drift | Medium | CI runs Maven and Gradle/frontend gates on every change |
| Large lists hurt WebView performance | Medium | Pagination/virtualization, measure before optimize |
| Per-OS rendering differences | Medium | Native screenshot/DOM/console checks on three targets |
| Shared SQLite opened by two UIs | High | Cross-entry-point single-instance lock + additive migrations |
| First jDesk release cannot downgrade | High | Keep JavaFX rollback artifact/schema compatibility through window |

## Completion Audit

Completion requires evidence for every checkbox in `spec.md`, every parity row and every release
gate. A green unit suite alone is not evidence for live WB, physical printing, CryptoPro or native
installer behavior.

Rollback tự động/dừng rollout khi có data loss/corruption, credential divergence, secret exposure,
security critical/high, launch failure lặp lại hoặc mandatory journey bị block. Rollback window
chỉ đóng sau 30 ngày ổn định ở 100% và rehearsal cuối; trước đó không xóa JavaFX/legacy credential.
