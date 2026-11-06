# Step160 — Backup backend SQL candidate (03/11/2026)

Baseline: Step159 archive SHA-256 `b2630d4bd3fe585c37e8d16a69deb4b5de689a002f71ba64b26aded8fcaa0af7`.
Scope: local candidate SQL + contract documentation + static verifier. No live Supabase changes.

## Implemented locally

- Added `supabase/candidates/step160_weekly_backup_backend_candidate.sql`; it is explicitly a candidate, not a numbered migration and not applied.
- Candidate defines private ZIP-only `inventory-backups` bucket (256 MiB cap), validates an existing bucket's configuration instead of silently changing it, and adds an owner-scoped immutable object contract.
- Storage INSERT requires active profile, exact owner folder, UUID ZIP filename, and a matching unexpired PENDING metadata registration through a checked helper.
- Storage SELECT is owner-only and uses operation-aware filters for object listing and authenticated download. No client UPDATE/upsert or DELETE policy is added.
- Metadata table has RLS enabled and direct grants revoked from `PUBLIC`, `anon`, and `authenticated`.
- `begin_inventory_backup` derives owner/path from `auth.uid()`, validates counts/size/digest, and supports exact idempotent retries while rejecting backup-ID metadata conflicts.
- `complete_inventory_backup` locks the caller's own pending metadata, checks matching Storage object owner and byte size, compares the client-reported digest to the registered digest, and records `CLIENT_DOWNLOAD_SHA256` as verification method.
- `list_my_inventory_backups` is owner-scoped. `list_inventory_backup_status_admin` requires active Admin profile + `app_admins` link and intentionally omits object path and checksum.
- Retention deletion is intentionally not included. No client DELETE permission is granted; a trusted server-side cleanup design is required before implementing retention.
- Added `docs/STEP160_BACKUP_BACKEND_CONTRACT.md` and `tools/verify_step160_backup_backend_candidate.py`.

## Verification

- Step160 static verifier: 30/30 PASS.
- Regression Step140–156, Step158 and Step159 verifiers: all PASS (individual counts are in `/mnt/data/step160_regression.log`).
- `python3 -m compileall -q tools`: PASS.
- No PostgreSQL parser or disposable database was available, so SQL syntax has not been executed against a database. The verifier is static and must not be represented as a database integration test.
- No Android compile, APK build/install, WorkManager test, real Storage upload/download, RLS impersonation test, or phone test was run. No Gradle used.

## Not done / safety boundary

- No SQL was sent to the connected Supabase project. No bucket, table, policy, RPC, object, Edge Function, or migration was created/changed/deployed.
- No user/profile, inventory row, image, or existing bucket was changed or deleted.
- No service-role key was requested or added.
- Client download-and-hash is recorded honestly as client-side verification; PostgreSQL does not recompute the ZIP bytes. A trusted server-side verifier would be needed for independent cryptographic attestation.
- Retention is not yet safe to enable because the trusted deletion mechanism has not been implemented.

## Next work

Step161 should review the candidate SQL carefully and design/test a trusted retention mechanism (or defer retention) before asking for permission to apply anything live. Then implement the Android uploader adapter against the exact Kotlin 3.0.1 API, with streamed/resumable file upload and download-to-private-temp SHA-256 verification. WorkManager scheduling remains separate and must not run until backend contract and client flow are integrated.
