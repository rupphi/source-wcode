# Signed Windows release runbook

This runbook provisions and rehearses the jDesk Windows release path. It does not authorize a real
release by itself. Never place a private key, PFX, password, PAT, or production license value in the
repository, command output, support bundle, or release asset.

## Trust material

Configure these GitHub Actions secrets for the source repository:

| Secret | Required format and purpose |
| --- | --- |
| `RELEASE_TOKEN` | Fine-grained token with only `contents:write` on `rupphi/relatest-wcode`. |
| `WINDOWS_SIGNING_CERTIFICATE` | Base64 of the Authenticode PFX. |
| `WINDOWS_SIGNING_PASSWORD` | PFX password. |
| `UPDATE_SIGNING_PUBLISHER` | Exact certificate Subject returned by `Get-AuthenticodeSignature`. |
| `UPDATE_MANIFEST_PRIVATE_KEY` | Base64 PKCS#8 DER Ed25519 private key. |
| `UPDATE_MANIFEST_PUBLIC_KEY` | Base64 X.509/SPKI DER public key matching the private key. |

Generate the manifest key offline in an approved secret-management environment. One compatible
OpenSSL flow is:

```bash
openssl genpkey -algorithm Ed25519 -out wcode-update-private.pem
openssl pkey -in wcode-update-private.pem -outform DER | base64
openssl pkey -in wcode-update-private.pem -pubout -outform DER | base64
```

Store the first Base64 result as the private-key secret and the second as the public-key secret.
Delete or vault the PEM according to the release-key policy. This key must never be the license
signing key. Existing clients pin one public key; do not rotate it until a preceding signed app
release adds and tests an explicit multi-key rotation window.

## Workflow contract

A `vMAJOR.MINOR.PATCH` tag runs `.github/workflows/release.yml` and must fail before publication if
any trust input is absent. The workflow:

1. Runs frontend lint, strict typecheck, tests, build and high-severity audit; Maven verify; Gradle
   tests, bindings, frontend build, doctor and the WCode package with offline recovery launcher.
2. Authenticode-signs and verifies both packaged launchers, then regenerates app-image checksums,
   CycloneDX and SPDX so their hashes describe the signed bytes.
3. Builds EXE and MSI from the jDesk app-image with upgrade UUID
   `23fbb124-e6d5-4f34-92f7-b0329d05f646`, then signs, timestamps and verifies both installers and
   the exact publisher.
4. Hashes the final signed MSI, creates `update-manifest.json`, signs its exact payload bytes with
   Ed25519 and independently checks version, size and SHA-256.
5. Uploads MSI, EXE, portable ZIP, app-image checksums, both SBOM formats and manifest to a draft
   release; only the final step makes that draft latest. A failed run must never publish an unsigned
   or partially uploaded latest release.

Never replace an installer after publishing its manifest. Publish a new patch version instead;
otherwise every pinned client correctly rejects the changed hash.

## Required Windows rehearsal

Before moving the updater parity row beyond Foundation, record evidence from a clean Windows x64
machine for all of the following:

1. Install signed jDesk N-1 and verify Authenticode publisher, Start menu entry and cold launch.
2. Seed an isolated non-production SQLite dataset; record counts, `PRAGMA integrity_check`, schema
   version and a redacted snapshot inventory.
3. Check N, verify no automatic download, download explicitly, cancel once and confirm no staged MSI
   remains and N-1 continues running.
4. Download again, confirm install separately, verify a fresh `signed-update-install` snapshot,
   successful same-upgrade-UUID replacement and cold launch of N with identical local data.
5. Exercise an installer failure/cancel and prove the current executable relaunches.
   Confirm the helper waits for the exact old WCode PID to exit before starting `msiexec.exe` and
   does not create a duplicate WCode process if shutdown exceeds the helper deadline.
6. Use the offline recovery CLI to verify the pre-install snapshot and perform a rehearsed restore;
   confirm integrity and data counts afterward.
7. Capture UI accessibility/overflow/console/network evidence without recording a path, shop name,
   token, license, device fingerprint, key, raw log, stack trace, or manifest signature.

If any check fails, keep the release draft or mark it non-latest, retain N-1, and ship a newly signed
patch after remediation. Do not weaken signature, publisher, size, hash, snapshot, or fixed-endpoint
checks to recover a release.
