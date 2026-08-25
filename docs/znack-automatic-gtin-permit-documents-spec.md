# Spec: Automatic GTIN permit documents for circulation

## Objective

WCode must obtain the permit documents registered on each GTIN product card and use those exact
documents when submitting `LP_INTRODUCE_GOODS`. A user must not enter or maintain a shop-wide
default goods document.

The implementation must prevent a document belonging to another GTIN, an expired document, or an
inactive document from being submitted for circulation.

## Source contract

- `/api/v4/true-api/product/gtin` remains the source of the shop's GTIN list. Its result items do not
  contain permit documents.
- National Catalog `/v3/feed-product?gtins=...` is the batched source of product-card attributes.
  Permit-document attributes are identified by:
  - `23557` -> `CONFORMITY_DECLARATION`
  - `23561` -> `CONFORMITY_CERTIFICATE`
  - `23765` -> `STATE_REGISTRATION_CERTIFICATE`
- National Catalog `/v4/rd-info-by-gtin` is the authoritative pre-submission check for the selected
  GTIN. Only status group `1` (green/valid for introduction), or the equivalent documented active
  status when the optional group is absent, may be submitted.
- `certificate_document_data` is an array. All distinct active permit documents returned for the
  GTIN are included in every product item in the introduction payload.

Official local documentation:

- `znack_api/ZnackAPIDocument_md/api-v5.62-05.06.2026-at-13-03-26.md`
- `znack_api/ZnackAPIDocument_md/True_API_GIS_MT-v676.0-05.06.2026-at-13-03-25.md`

## Tech stack

- Java 25 and JavaFX 25.0.2
- Maven Wrapper
- Gson 2.13.2 for API/JSON persistence
- SQLite JDBC 3.45.3.0
- OkHttp 4.12.0
- JUnit 5.12.1 and MockWebServer

No new dependency is required.

## Commands

```bash
# Focused tests while implementing
./mvnw -B -Dtest=ZnackModuleTest,ZnackGtinWorkflowTest,DatabaseMigrationCompatibilityTest test

# FXML/controller verification after removing manual document fields
./mvnw -B -Dtest=FxmlSmokeTest,ZnackModuleTest test

# Required full verification
./mvnw -B clean verify
```

## Project structure

- `src/main/java/.../integration/znack/` -> API client, parser, models, persistence and circulation
- `src/main/java/.../ui/znack/` -> JavaFX controller
- `src/main/resources/.../ui/znack/` -> FXML
- `src/test/java/.../integration/znack/` -> synchronization and circulation tests
- `src/test/java/.../config/` -> additive SQLite migration tests

## Code style

Use immutable records and constructor-injected dependencies. Keep document parsing in one domain
component so synchronization and pre-submission verification use the same type mapping.

```java
public record GoodsDocument(String type, String number, String date) {
    public boolean complete() {
        return !blank(type) && !blank(number) && !blank(date);
    }
}
```

## Functional behavior

1. During GTIN synchronization, WCode parses every supported permit-document attribute returned in
   `good_attrs` and stores the complete list against that shop and GTIN.
2. A successful card response with no permit-document attributes clears previously synchronized
   documents. A failed/partial catalog request does not erase the last successful data.
3. Immediately before creating an introduction document, WCode calls `/v4/rd-info-by-gtin` with the
   same GTIN and the configured owner/participant INN.
4. WCode replaces the stored list with the distinct currently active documents returned by that
   check and builds `certificate_document_data` from that list.
5. WCode never falls back to `znack_settings.document_number`, `document_date`, or `document_type`.
6. If no active permit document is available, WCode does not submit an introduction document. The
   already purchased KIZ codes remain retryable and the error tells the user to correct/publish the
   permit document on the National Catalog GTIN card.
7. Retrying introduction repeats the authoritative lookup, so no manual WCode metadata edit is
   required after the National Catalog card is corrected.
8. The settings UI no longer displays the default document number/date fields. Legacy database
   columns remain readable for backward-compatible migration but are not used for new submissions.
9. A successfully fetched GTIN card without any complete permit document is synchronized into the
   existing trash view instead of the operational catalog. A failed catalog batch does not change
   the eligibility of previously synchronized GTINs and does not activate new unverified GTINs.

## Persistence

- Add one nullable JSON column to `znack_products` for the complete synchronized permit-document
  list. The migration is additive.
- Existing single-document columns remain in place to avoid destructive migration and may mirror
  the first synchronized document for legacy display compatibility.
- Product deletion continues to remove the document data with the product row.

## Testing strategy

- Parser tests for every supported `attr_id`, legacy `number:::date` values, duplicates and malformed
  attributes.
- Repository tests for multiple documents, empty-list clearing, shop isolation and old-schema
  migration.
- Introduction tests proving that multiple active documents are emitted and shop defaults are never
  used.
- Failure tests for missing, expired, inactive and foreign-GTIN documents.
- Coordinator tests proving missing documents do not trigger another KIZ purchase and remain
  retryable after the National Catalog card is corrected.
- FXML smoke test proving settings load after removing manual fields.

## Boundaries

### Always

- Bind documents to `(shop_id, gtin)`.
- Refresh authoritative document state before every new introduction submission.
- Preserve downloaded KIZ codes when document lookup or validation fails.
- Redact upstream payloads and identifiers from logs according to existing sanitizer rules.

### Ask first

- Supporting a product group other than the existing `lp` flow.
- Submitting a yellow, red or unknown-status permit document.
- Reintroducing a manual document override.

### Never

- Use a shop-wide default document as a fallback.
- Choose a document from another GTIN.
- Submit an incomplete or inactive document.
- Modify the user's live `app/database.db` during tests.

## Success criteria

- A GTIN with two active declarations/certificates produces two matching entries in
  `certificate_document_data` without user input.
- A stale shop default cannot appear in an introduction payload.
- A GTIN with no active registered document is blocked before submission with an actionable,
  retryable status; purchased codes are retained.
- A GTIN card with no complete permit document is visible in Trash and absent from operational
  mapping and purchase lists after synchronization.
- Correcting the GTIN card and retrying succeeds without editing WCode settings.
- Existing databases migrate additively and pass SQLite integrity/foreign-key checks.
- Focused tests, FXML smoke tests and `./mvnw -B clean verify` pass.

## Assumptions requiring approval

1. The current scope is the existing `lp` (light industry) `LP_INTRODUCE_GOODS` workflow.
2. WCode should submit all active permit documents registered for the GTIN, not arbitrarily select
   only the first one.
3. Missing, inactive or unverifiable documents must block submission and remain retryable; WCode
   must not silently continue or use legacy defaults.
4. The production National Catalog base is the officially documented
   `https://апи.национальный-каталог.рф`; the documented sandbox base is used when running against a
   sandbox True API configuration.

## Implementation tasks

- [x] Add failing parser/model tests and implement the shared permit-document parser.
- [x] Add failing migration/repository tests and persist the per-GTIN document list additively.
- [x] Add failing synchronization tests and extract documents from `feed-product` batches.
- [x] Add failing circulation tests and verify active documents through `rd-info-by-gtin` before
      building the payload.
- [x] Remove default-document input fields and update FXML/controller tests.
- [x] Run focused tests and the complete Maven verification suite.
