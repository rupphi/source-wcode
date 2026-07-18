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
| App lifecycle/close guard | `MainApplication`, `Launcher` | smoke start/stop, busy-work close veto, recovery | Foundation |
| Workspace shell/navigation | `HomeController`, `home-view.fxml` | all destinations, responsive/keyboard, persisted selection | Foundation |
| Shop sidebar/header | `ShopSidebarController`, `WorkspaceHeaderController` | list/select/edit/delete/sync, no secret in bridge | Foundation |
| Dashboard | `DashboardController`, `DashboardRepository` | local KPIs + live refresh + all async states | Foundation |
| WB overview sync | `WbSyncWorkflow` | read-only live sync, progress/cancel/retry, KPI refresh | Foundation |
| Shop CRUD/token | `ShopDialogService`, `ShopWorkflow` | validated CRUD, masked secret, OS-store migration/rollback | Legacy |
| Supply list | `SupplyListController` | paginated local list/search/status + detail selection/list-state restore native evidence | Foundation |
| Supply detail/orders | `SupplyDetailController` | local detail/search/natural sort/page, opaque image assets and live WB refresh native evidence; GTIN pending | Foundation |
| Excel order import | `ExcelOrderImportService`, `OrderImportWorkflow` | native open dialog, bounded XLSX parser, opaque session, live stickers, server paging/search, no path/secret crossing bridge | Foundation |
| Deliver supply | `SupplyDetailController.onDeliver` | explicit-confirm mutation test on approved shop | Legacy |
| FBS packing | `PackingController` | read-only new/preparation/dispatch board, search/category/paging and supply detail are native-tested; selection/create/add/deliver + KIZ preflight pending | Foundation (read) |
| Print/export | `OrderExportWorkflow`, print services | macOS live WB PDF/save/open passed; physical 58×40 Windows test pending | Foundation |
| Print history/reprint | `PrintHistoryController` | bounded history/search/status page and native reprint/save/open pass on live KingRussia job; unsupported-image regression remains covered by legacy service test; Windows native evidence pending | Foundation |
| Template designer | `PrintTemplateDesignerController` | typed FBS/FBO catalog, mm inspector and responsive 58×40 read preview are native-tested; CRUD/elements/editing/layout persistence pending | Foundation (read) |
| FBO packing | `FboPackingController` | search/category/pagination/quantity/single+batch print | Legacy |
| GTIN/KIZ mapping | `KizMappingController`, `KizGtinMappingEditor` | inventory/filter/edit/sync/progress/error | Legacy |
| Znack settings/products | `ZnackAutomationController` | settings, signature cert, product lifecycle | Legacy |
| Znack orders/logs | `ZnackAutomationController` | pipeline state/retry/logs/recovery | Legacy |
| CryptoPro signing | `integration/znack/signature` | real Windows CryptoPro provider matrix | Legacy |
| License | `LicenseDialogService`, `LicenseService` | activate/refresh/offline grace/expiry/error | Legacy |
| Update | `UpdateDialogService`, `UpdateInstallerService` | signed download/install/rollback on Windows | Legacy |
| Language/theme | `I18nService`, `ThemeService` | RU/EN/VI/ZH, light/dark/system persisted | Legacy |
| Error report/diagnostics | `ErrorReportDialog`, `ReportApiClient` | redacted support flow, no secret/stack leak | Legacy |
| Packaging | Maven `release.yml`, jpackage | jDesk EXE/MSI/portable, checksum/SBOM/sign/upgrade | Foundation |

## Direct JavaFX dependency inventory

The jDesk build must initially exclude these groups and reduce the list after each parity slice:

- Entry: `Launcher`, `MainApplication`.
- Shared: `AlertService`, `AppTaskExecutor`, `FxmlViewLoader`, `ThemeService`.
- UI: all direct JavaFX classes under `ui/`.
- Feature UI adapters: `KizAttachmentCoordinator`, `PrintOptionsDialogService`,
  `PrintTemplateDesignerService`, `ShopDialogService`, `UpdateDialogService`.

Removing JavaFX is complete only when a repository-wide search finds no JavaFX/FXML/MaterialFX/
Ikonli production reference and every row above is `Cutover`.
