# WB → National Catalog card registration (test scope)

## Confirmed data model

- A WB `vendorCode` (article) represents the color-level product card in the current seller catalog.
- Every real WB size is identified by `sizes[].chrtID`; therefore WCode creates one National Catalog card/GTIN per existing size, never a Cartesian product of colors and sizes.
- This test stops after the National Catalog card is published. It deliberately does not change the WB card or its `sizes[].skus` values.

## Guarded test workflow

1. Read synchronized WB cards and show one row per `chrtID`.
2. Ask for TN VED and resolve active National Catalog categories through the authenticated True API gateway `GET /api/v3/true-api/nk/categories?tnved=...`.
3. Load the current mandatory attribute model through `GET /api/v3/true-api/nk/attributes?cat_id=...&attr_type=m`; do not hard-code one apparel schema.
4. Authenticate using the certificate assigned to the selected shop and call `GET /v3/generate-gtins?exist=1` to check the current monthly GS1/GTIN quota without consuming a new number.
5. Auto-fill values from the WB card. The user completes every missing mandatory value and chooses either declaration attribute `23557` or certificate attribute `23561` with `number:::YYYY-MM-DD`.
6. Generate one GTIN. Persist it locally before the first feed request so a timeout or restart cannot generate a duplicate.
7. Submit one test entry to `POST /v3/feed`, persist `feed_id`, and poll `GET /v3/feed-status?verbose=true&feed_id=...` every 15 seconds.
8. When moderated, get XML using `POST /v3/feed-product-document`, sign the raw XML with a detached PKCS#7 signature, and submit it with `POST /v3/feed-product-sign-pkcs`.
9. Mark the local row as `PUBLISHED`, keep its GTIN/feed/good checkpoints, and stop. Updating the WB card is outside this test.

## Naming rule used by the test

`<WB title>, <brand>, арт. <vendorCode>, цвет <color>, размер <size>`

The value is editable before creation. This keeps size cards unique and readable while preserving the seller's WB terminology.

## Safety and operational limits

- No GTIN is generated before certificate authentication, quota, TN VED, category, document and every mandatory category attribute pass local validation.
- `/v3/feed` supports at most 500 entries; the test deliberately submits one row at a time.
- National Catalog signing endpoints accept at most 10 cards; the test signs one row at a time.
- The test executable has a separate Windows application identity and stores data under `WCodeZnackRegistrationTestData`. It never scans or migrates production `WCodeData`/legacy directories and never offers a production auto-update.
- No request to `POST /content/v2/cards/update` exists in the test registration workflow.
- The registration table stores GTIN, payload, feed ID, good ID and status per `(shop_id, chrt_id)`. Opening the tab resumes in-progress rows from the stored checkpoint.

## Official references

- National Catalog API: https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/
- WB marking-card changes: https://dev.wildberries.ru/release-notes
