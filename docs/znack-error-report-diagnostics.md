# Znack error report diagnostics

## Objective and acceptance
Admin reports must retain the request method/URL, submitted payload and response for
Znack HTTP failures, transport failures, malformed JSON and rejected registration
feeds. A missing response must be identified as unavailable, not invented. Reports
opened from stored registration errors must retain the same diagnostic context.
The dialog displays a short summary but sends/copies sanitized diagnostics.
Purchase-pipeline/order errors with HTTP context retain that context too; ordinary
workflow status text keeps its existing representation. Diagnostic reports are capped
at 120,000 characters, with a truncation marker. Authentication bodies are omitted.

## Implementation plan
1. Add bounded, sanitized HTTP context attached to the original exception, preserving
   exception types and retry decisions. Verify HTTP, malformed JSON and timeout paths.
2. Retain feed-status responses and attach the submitted registration payload on
   rejection. Persist formatted registration errors. Verify rejection and storage.
3. Send full diagnostics through the existing report message field, with a final
   sanitization boundary. Verify the actual outgoing report body using a local server.

## Structure and style
Use existing Java 25, Gson, OkHttp and JUnit dependencies. Source is under
`src/main/java/com/tuandev/fbsbarcode/integration/{znack,license}` and matching tests
under `src/test/java`. Follow existing small classes and methods, for example
`String details = ZnackErrorDetails.format(error);`.

## Boundaries
Do not send reports automatically or change report-server field contracts. Do not capture
authentication headers. Sanitize credentials in URL query parameters, JSON and plain
text before retaining diagnostic snapshots. Bound each body to 24,000 characters,
mark truncation and avoid shared/global request history across shops or workers.
Existing failures without recorded requests cannot be reconstructed retrospectively.
The license server must retain up to 128,000 message characters (formerly 4,000),
with a 1 MiB HTTP body limit only for reports; other routes retain their 64 KiB limit.
Run `npm test` in `license-server/` to verify long Cyrillic reports reach the admin API.

## Verification
Use regression tests for secret redaction, payload/response preservation, transport
errors, bounded output, feed rejection and report HTTP serialization.
Run `mvn -o -Dtest=ZnackErrorDetailsTest,ZnackRequestDiagnosticsTest,ZnackNationalCatalogServiceTest,ReportApiClientTest test`.
Run CI-equivalent `mvn -o -B '-Dsurefire.excludes=**/FxmlSmokeTest.java' clean verify`
and `mvn -o -DskipTests package` for packaging if an unrelated test blocks verify.
