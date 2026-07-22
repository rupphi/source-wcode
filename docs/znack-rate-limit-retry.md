# Spec and plan: Znack HTTP 429 recovery

## Objective

Prevent Znack National Catalog synchronization from leaving GTIN names and TN VED metadata blank when the API rate limit is reached. Safe GET requests retry automatically, while chargeable or state-changing requests remain single-attempt.

## Source contract

The bundled National Catalog API documentation states that:

- request limits are scoped both globally per organization and per method;
- a limit window can last up to five minutes from the first request in the series;
- usage is exposed through `API-Usage-Limit` and `API-Method-Usage-Limit` response headers;
- HTTP 429 includes `Retry-After` as seconds until access is restored;
- `feed-product` accepts at most 25 GTINs per request.

Source: `znack_api/ZnackAPIDocument_md/api-v5.62-05.06.2026-at-13-03-26.md`, sections 2.1, 2.5 and 3.1.1.

## Behavior

1. Retry HTTP 429 only when the HTTP method is idempotent (`GET` or `HEAD`).
2. Prefer a valid non-negative `Retry-After` value in seconds.
3. When `Retry-After` is absent or malformed, use bounded exponential fallback delays.
4. Limit attempts and total retry delay to prevent an unbounded background task. The total delay must not exceed the documented five-minute limit window.
5. Preserve thread interruption and stop retrying immediately when interrupted.
6. Never apply the 429 retry policy to order creation, document submission, or other state-changing requests.
7. If catalog enrichment still exhausts retries, preserve successful batches but record the overall GTIN sync as `WARN`/partial rather than `INFO`/complete.
8. Keep the documented 25-GTIN catalog batch size.
9. Prioritize persisted active GTINs with a blank name or TN VED before already-complete GTINs so a repeated sync repairs missing metadata before consuming the catalog request budget.

## Testing strategy

- Use a localhost HTTP server; no external Znack calls.
- Prove idempotent retry, seconds parsing, bounded fallback and exhaustion.
- Prove a POST receiving 429 is attempted only once.
- Prove partial catalog sync preserves successful metadata and records a warning.

## Commands

- Targeted integration test: `./mvnw -Dtest=ZnackModuleTest test`
- Full CI/release verification: `./mvnw -B verify`

## Boundaries

- Always sanitize API errors in logs, preserve interruption and keep retries bounded.
- Never log tokens, raw KIZ values, or retry order/document creation automatically.
- Runtime `database.db` is created in the per-user application-data directory and is not distributed from source control.
