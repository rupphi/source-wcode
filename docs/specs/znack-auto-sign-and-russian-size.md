# Znack signing and WB size correction

Approved scope: automatic signing after moderation for each shop, and Russian WB sizes for registration.

Acceptance:
- Registration attributes and full names use wbSize, never techSize. Missing Russian size is reported during preflight.
- Length-width is limited to scarf/towel categories with both positive item dimensions and a supporting catalog schema. Hyphens in clothing sizes do not select length-width.
- Auto-sign is automatic for all shops without a toggle or stored opt-in. Only an authorized signing session, unchanged credentials, matching GTIN/owner/good ID, and a card awaiting signature may sign.
- Publication is re-read on a later tick before WB write-back. Timeouts reconcile remote state before another signature attempt.
- Existing queued/submitted payloads are not silently rewritten. Corrections apply to newly prepared registrations.

Implementation plan:
1. Reproduce size errors, fix mapper, run registration tests.
2. Remove the per-shop preference and toggle; wire verified publication monitoring to the existing signing API with document identity validation.
3. Test signing gates, response identity and publication states; run full local verification excluding the GUI smoke test requiring a graphical runtime.

API reference: https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/ (v5.67), feed-product-document / feed-product-sign-pkcs.

Validation: size regression tests failed before the correction. Full local `mvn -o -B '-Dsurefire.excludes=**/FxmlSmokeTest.java' clean verify` passed: 572 tests, zero failures/errors. Signing tests use fake API responses and a fake signer, including signing identity changes before submission, mismatched/empty/ambiguous documents, rejected signatures, and timeout without immediate replay. No live cards were signed. Graphical smoke test and live Windows CryptoPro verification remain outside this local run.
