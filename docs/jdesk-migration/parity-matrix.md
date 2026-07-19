# JavaFX → jDesk Parity Matrix

Baseline: WCode `1.1.7`, branch point `61e8117`, 2026-07-18.

## Baseline evidence

- `./mvnw -B verify`: **PASS**, 195 tests, 0 failures/errors/skips.
- Java sources: 180 total; 30 import JavaFX/MaterialFX/Ikonli directly.
- UI resources: 12 FXML views + JavaFX theme CSS.
- Existing live database (read-only inventory): 8 shops, 17.922 products, 8.938 supplies,
  52.713 orders. No API key value was printed.

## Status legend

- `Legacy`: JavaFX implementation only.
- `Foundation`: contract or shell exists, not user-flow complete.
- `Parity`: automated and native evidence covers the current behavior.
- `Cutover`: jDesk is production implementation and legacy code is removed.

## Feature matrix

| Area | Legacy source/oracle | Required jDesk evidence | Status |
| --- | --- | --- | --- |
| App lifecycle/close guard | `MainApplication`, `Launcher` | shared JavaFX/jDesk writer gate, explicit schema revision, verified first-launch/pre-writer rollback points, smoke start/stop, busy-work close veto and recovery pass; Windows lifecycle evidence remains pending | Foundation |
| Workspace shell/navigation | `HomeController`, `home-view.fxml` | all destinations, responsive/keyboard and persisted selection pass; production entry is bundle-only, dev URL is strict loopback and real WKWebView blocks remote main-frame/popup without losing the React root | Foundation |
| Shop sidebar/header | `ShopSidebarController`, `WorkspaceHeaderController` | bounded list, persisted selection and accessible create/edit/confirmed-delete manager pass Java/React plus isolated native lifecycle; no secret is returned through the bridge | Foundation |
| Dashboard | `DashboardController`, `DashboardRepository` | local KPIs + live refresh + all async states | Foundation |
| WB overview sync | `WbSyncWorkflow` | read-only live sync, progress/cancel/retry, KPI refresh | Foundation |
| Shop CRUD/token | `ShopDialogService`, `ShopWorkflow` | validated serialized CRUD, write-only token form, atomic selection/cascade and async-job exclusion pass; monotonic version/fingerprint, OS-store read-back/reconcile and token-free tombstones pass fault tests plus isolated macOS Keychain lifecycle; Windows credential-store evidence and post-rollback plaintext retirement remain pending | Foundation (dual-write rollback) |
| Supply list | `SupplyListController` | paginated local list/search/status + detail selection/list-state restore native evidence | Foundation |
| Supply detail/orders | `SupplyDetailController` | local detail/search/natural sort/page, opaque image assets and live WB refresh; bounded local GTIN inventory/search/page plus license/certificate-gated shared purchase confirmation pass React and isolated browser bridge tests without paid calls | Foundation |
| Excel order import | `ExcelOrderImportService`, `OrderImportWorkflow` | native open dialog, bounded XLSX parser, opaque session, live stickers, server paging/search, no path/secret crossing bridge | Foundation |
| Deliver supply | `SupplyDetailController.onDeliver` | supply detail now exposes the same exact-supply one-use preview/confirmation as packing; Java/React tests and an isolated browser bridge fixture prove print+KIZ blockers suppress execute, mismatched responses fail closed and closed supplies expose no action; approved live WB mutation remains pending | Foundation (guarded mutation) |
| FBS packing | `PackingController` | new/preparation/dispatch board plus exact string-ID selection, searchable open-supply chooser and one-use confirmed create/add/deliver commands are Java/React-tested; isolated WKWebView proves print+KIZ blockers suppress deliver with clean console/DOM, while approved live WB mutation remains pending | Foundation (guarded mutation) |
| Print/export | `OrderExportWorkflow`, print services | macOS live WB PDF/save/open passed; physical 58×40 Windows test pending | Foundation |
| Print history/reprint | `PrintHistoryController` | bounded history/search/status page and native reprint/save/open pass on live KingRussia job; unsupported-image regression remains covered by legacy service test; Windows native evidence pending | Foundation |
| Template designer | `PrintTemplateDesignerController` | typed FBS/FBO catalog; CRUD/default/reset; palette/add/copy/paste/delete; bounded mm inspector, drag/resize geometry and persisted 58×40 layout are covered by Java/React tests plus native isolated-SQLite lifecycle at 1440×900 and 960×640 | Parity |
| FBO packing | `FboPackingController` | typed local search/category/50-SKU paging, quantity retention, quick+batch native export/open and KIZ compensation are covered by Java/React tests, a real two-page 58×40 PDF integration test and live native catalog evidence at 1440×900/960×640; physical Windows output remains the shared print gate | Parity |
| GTIN/KIZ mapping | `KizMappingController`, `KizGtinMappingEditor` | bounded local inventory/filter/page and conflict-safe wildcard/exact editor are covered by Java/React tests plus live read-only and isolated native lifecycle evidence; supply detail and mapping workspace share the same fail-closed catalog validator, while purchase uses the guarded Znack flow | Foundation (local) |
| Znack settings/products | `ZnackAutomationController` | safe settings, bounded active/deleted catalog, atomic hide/restore, opaque certificate discovery/test and resumable participant product-sync commands are covered by Java/React/SQLite tests plus isolated native safe-state evidence; real Windows CryptoPro/sync evidence and permanent purge remain pending | Foundation (automation) |
| Znack orders/logs | `ZnackAutomationController` | persisted purchase UUID, replay-safe order creation, authoritative progress, introduction-only retry and redacted bounded journal are covered by Java/React/SQLite tests plus isolated native seeded-state evidence; real paid purchase/introduction still requires an approved test artifact | Foundation (automation) |
| CryptoPro signing | `integration/znack/signature` | real Windows CryptoPro provider matrix | Legacy |
| License | `LicenseDialogService`, `LicenseService` | existing Ed25519 state oracle, eight bounded states, activation/refresh, explicit best-effort deactivation and paid-KIZ gate are covered by Java/React tests plus isolated native not-activated evidence; real activation/deactivation remains approval-only | Foundation |
| Update | `UpdateDialogService`, `UpdateInstallerService` | dedicated Ed25519 manifest verify-before-parse, fixed release endpoint, bounded owner-only streamed MSI with exact size/SHA-256, explicit check/download/cancel/second install confirmation, shared optional-version skip, repeated Authenticode publisher check and fresh locked snapshot are covered by Java/React/Node tests; clean Windows x64 N-1 → N, cancellation/failure relaunch and rollback evidence remain pending | Foundation (signed automation) |
| Language/theme | `I18nService`, `ThemeService` | bounded shared `app_language`/`app_theme` persistence, RU/EN/VI/ZH shell/settings/license plus complete FBS supplies and packing journeys and dark/light/system runtime behavior are covered by Java/React tests plus browser/native restart evidence; other feature-specific Russian copy remains pending | Foundation (shared + FBS) |
| Error report/diagnostics | `ErrorReportDialog`, `ReportApiClient` | legacy auto-upload is replaced by explicit local summary/native ZIP export; allowlisted read-only SQLite aggregate, atomic non-symlink writer, cancellation, bridge/DOM redaction and isolated macOS native save evidence pass with no path, identity, token, license, log or stack crossing the bridge | Parity |
| Packaging | Maven `release.yml`, jpackage | explicit `wcode.desktop` JPMS root and trimmed-runtime dry-run remove `ALL-UNNAMED`; main grants only platform+SQLite native access and recovery only SQLite under deny-mode. Release workflow gates Maven/Gradle/frontend/audit, tracks package/runtime inputs, rejects stale main JAR bytes, embeds recovery, signs/verifies launchers + EXE + MSI, regenerates checksums/SBOMs and publishes through a draft; first provisioned Windows release remains pending | Foundation (signed workflow) |

## Direct JavaFX dependency inventory

The jDesk build must initially exclude these groups and reduce the list after each parity slice:

- Entry: `Launcher`, `MainApplication`.
- Shared: `AlertService`, `AppTaskExecutor`, `FxmlViewLoader`, `ThemeService`.
- UI: all direct JavaFX classes under `ui/`.
- Feature UI adapters: `KizAttachmentCoordinator`, `PrintOptionsDialogService`,
  `PrintTemplateDesignerService`, `ShopDialogService`, `UpdateDialogService`.

Removing JavaFX is complete only when a repository-wide search finds no JavaFX/FXML/MaterialFX/
Ikonli production reference and every row above is `Cutover`.
