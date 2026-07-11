# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**WCode / FBSBarcode** — a JavaFX 25 desktop app (Java 25, Maven) for Wildberries FBS
(Fulfillment-by-Seller) sellers. It syncs supplies/orders/products from the Wildberries API,
prints 58×40mm order labels combined with **KIZ** DataMatrix codes (Russian Честный ЗНАК /
"Znack" marking), and automates buying & attaching those KIZ codes. Data lives in an embedded
SQLite database. Ships as Windows EXE/MSI/portable installers.

> Note: this is its own git repo (root = this `WCode/` directory), distinct from the parent
> `wcode-saas/` web SaaS (which has its own `backend/`+`frontend/` and a separate CLAUDE.md).
> The parent's "Verify before push" gradle/npm commands do **not** apply here.

## Commands

Use the Maven wrapper (`./mvnw`, or `mvnw.cmd` on Windows). Requires **JDK 25**.

```bash
./mvnw clean javafx:run        # run the app (launches com.tuandev.fbsbarcode.Launcher)
./mvnw verify                  # what CI runs — compile + full test suite (build-java.yml)
./mvnw clean package           # build runnable jar → target/FBSBarcode-<version>.jar + target/lib/
./mvnw test -Dtest=KizServiceTest                 # single test class
./mvnw test -Dtest=KizServiceTest#methodName      # single test method
```

Native installers (run on the matching OS; jpackage must be on PATH):

```bash
./build.sh exe | msi | app-image | dmg | pkg   # macOS/Linux wrapper around jpackage
build.bat exe | msi | app-image                # Windows
```

**CI** (`.github/workflows/build-java.yml`) runs `./mvnw.cmd -B verify` on `windows-latest` for
pushes to `main` and PRs. **Release** (`release.yml`) fires on `v*` tags, building the Windows
installers and uploading them to GitHub Releases (`tuanworlddev/-WCode-Znack`). Bump the version
in **`pom.xml`** (`<version>`, `app.version`) before tagging; `BuildConfig` reads it at runtime
from the filtered `app.properties` resource.

## Architecture

Single package root: `com.tuandev.fbsbarcode`. Layers:

- **Entry point** — `Launcher.main` sets up safe temp/cache dirs and a non-ASCII `user.home`
  workaround (important for Cyrillic Windows usernames + SQLite/JavaFX native temp files), runs
  `AppDataRecoveryService`, then launches `MainApplication` (the JavaFX `Application`).
  `MainApplication` loads `ui/workspace/home-view.fxml` → `HomeController`, the central
  orchestrator wiring every feature controller together.

- **`config/Database.java`** — the single source of truth for the SQLite schema. `getConnection()`
  opens `<appDataDir>/database.db` with WAL mode. `initDatabase()` (called once from
  `HomeController.initialize`) creates tables idempotently and performs **lightweight in-place
  migrations** via `ensureColumnExists` / `createIndexIfNotExists` / `dropLegacyKizTables`.
  WB and Znack tables are owned by `WbSchemaSupport.initialize` and `ZnackSchemaSupport.initialize`.
  **When adding a column or table, extend the relevant `initialize`/migration path here — never
  assume a fresh DB**; `DatabaseMigrationCompatibilityTest` guards this.

- **`integration/wb/`** — Wildberries API client + sync. `WbApiClient` (OkHttp+Gson) talks to WB;
  rate limiting via `WbContentApiRateLimiter`. `Wb*SyncService` classes pull products/supplies/
  orders incrementally, tracked by `WbSyncStateRepository`/`WbShopSyncState`. `WbSyncWorkflow` and
  `WbSupplyWorkflow` are the high-level orchestrators the UI calls. `Wb*Repository` classes persist
  to SQLite.

- **`integration/znack/`** — Честный ЗНАК ("Znack") marking-code automation. `ZnackApiClient` +
  `ZnackAuthService` authenticate; `ZnackPurchaseCoordinator` runs a background polling **pipeline**
  (a daemon `ScheduledExecutorService`) that drives KIZ purchase through `PurchaseStage`s, with
  `ZnackKizOrderService`/`ZnackKizCodeService`/`ZnackIntroductionService`. CIS/GTIN normalization
  lives in `ZnackCisNormalizer`/`GtinNormalizer`. **`integration/znack/signature/`** wraps CryptoPro
  CAdES signing (Windows) used to sign Znack documents — `CryptoProSignatureProvider` shells out via
  `CryptoProCommandRunner`.

- **`integration/update/`** — in-app self-update: checks GitHub Releases (`UpdateApiClient`,
  `VersionComparator`) and on Windows downloads + launches the installer (`UpdateInstallerService`).

- **`features/`** — domain logic grouped by area: `shop`, `supply`, `order`, `print`, `kiz`,
  `kizmapping` (GTIN↔KIZ category mapping), `fbo`, `packing`, `dashboard`. PDF generation
  (`*PdfExporter`, `BarcodePrintService`, iText 8) and barcode/QR/DataMatrix rendering (ZXing) live
  in `features/print` and `features/fbo`. Print layouts are user-editable templates stored as
  `layout_json` (`PrintTemplate*`, designer in `ui/print`).

- **`ui/`** — JavaFX controllers + matching FXML under `resources/.../ui/<area>/`. Controllers are
  paired by name (`FooController.java` ↔ `foo-view.fxml`); load via `FxmlViewLoader`.

- **`shared/`** — cross-cutting services: `AppPaths` (all on-disk locations; respects the
  `wcode.appdata.dir` system property — used for test isolation), `AppTaskExecutor` (background
  task pool; `MainApplication` blocks close while tasks run), `I18nService` (i18n: en/ru/vi/zh in
  `resources/.../i18n/messages_*.properties`), `ThemeService`, `ConfigService`, `AlertService`.

- **`models/`** — plain domain types (`Shop`, `Order`, `Kiz`, `Sticker`).

### Conventions worth knowing
- **Background work runs off the FX thread** via `AppTaskExecutor` / JavaFX `Task`; only touch UI on
  the FX application thread. Long pipelines (Znack purchase, WB sync) are daemon executors.
- **All user-facing text is localized** — add keys to every `messages_*.properties` and resolve via
  `I18nService.tr(...)`; don't hardcode strings.
- **Never write to arbitrary filesystem paths** — go through `AppPaths`. The non-ASCII home / safe
  temp setup in `Launcher` exists to keep native libs working on localized Windows.

### Tests
JUnit 5 (`./mvnw verify`). UI/FXML tests (`ui/FxmlSmokeTest`, `KizMappingEditorTest`) boot the
toolkit with `Platform.startup` and point `wcode.appdata.dir` at a JUnit `@TempDir` so they use a
throwaway SQLite DB. Follow that pattern (set `wcode.appdata.dir` to a temp dir) for any test that
touches `Database`/`AppPaths`. Test packages mirror `main` (`features/`, `integration/`, `ui/`,
`shared/`, `config/`).
