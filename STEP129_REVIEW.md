# Step129 Review – Deny client-side deletion of private part images

Date: 2026-11-03

## Baseline and scope
- Continued from the exact Step128 archive after verifying its SHA-256 (`86e9e15bfbb8dc6cd95fba11d5e0dfaf6f05eeae24ff18a52756e9d08c5f101b`) and running `unzip -tq` successfully.
- Reviewed every Storage policy migration in the repository, Android source references to part-image deletion, and current Supabase Edge Functions.
- Added migration `202611030014_deny_authenticated_part_image_deletes.sql`: removes the historical permissive owner-folder DELETE policy and installs a restrictive `DELETE` policy for `anon` and `authenticated` that denies deletion from `part-images` while leaving other buckets outside this guard.
- Existing restrictive owner/active-profile guard remains unchanged and continues to fence part-image access to the authenticated owner's first path segment.
- Added `docs/PHOTO_CLOUD_GARBAGE_COLLECTION_CONTRACT.md` defining why absence from `parts.photo_path` is insufficient, the retained references that must be considered, and prerequisites for any future server-side collector.
- Added `tools/verify_step129_server_only_photo_delete.py`.
- No Android Kotlin, Edge Function, current inventory data, Storage object, operation ledger, change-feed event, or restore session was modified. No candidate queue, scheduler, or garbage collector was implemented.

## Security behavior
- Authenticated KTV/Admin and anonymous client roles cannot delete objects in `part-images` through the Storage API after this migration is deployed.
- The restrictive policy is scoped to `FOR DELETE` and the `part-images` bucket; it does not alter other buckets or the existing SELECT/INSERT/UPDATE owner-folder policies.
- A trusted server-side function using service credentials outside the APK may later perform a reference-aware delete, because privileged service-role access bypasses ordinary client RLS. No such function exists yet.
- No actual cloud object deletion is performed by this step.

## Why garbage collection remains deferred
- Current parts, immutable `sync_operations.payload/result`, append-only `inventory_sync_events.payload`, restore staging, and in-flight/ambiguous operations can all retain photo references.
- The repository has no ledger/change-feed retention contract, cursor-expiry/resnapshot protocol, trusted collector, quarantine queue, or live PostgreSQL/Storage staging environment.
- Age-only deletion or checking only `parts.photo_path` would risk deleting an object still needed for replay, a lagging device, or a resumable restore.

## Verification
- Step129 server-only photo delete verifier: 18/18 PASS.
- Step128 lifecycle retention verifier: 19/19 PASS.
- Step127 retry/idempotency verifier: 18/18 PASS.
- Step126 photo read-back verifier: 17/17 PASS.
- Step95 Storage/Admin guard verifier: 15/15 PASS.
- Step85 photo/Outbox race: 13/13 PASS.
- Step90 session fencing: 10/10 PASS.
- Step106 logout/in-flight cancellation: 13/13 PASS.
- Step49–72 sync: 24/24 PASS + SQLite schema/atomic replacement smoke PASS.
- Python tooling `compileall`: PASS.
- Step128 archive SHA-256 and ZIP integrity: PASS.

## Not verified / deployment requirements
- SQL migration syntax and RLS behavior were not executed against PostgreSQL/Supabase staging; `psql` is unavailable in this environment. Static source checks do not substitute for a live Storage API delete-denial test using anon/authenticated sessions and a service-role test.
- Before deployment, inspect the actual hosted `pg_policies`, table grants, bucket configuration, and role privileges; confirm no out-of-repository permissive or privileged path allows client deletion. Verify service-role credentials are held only in trusted server secrets.
- No Edge Function or scheduler deployment, Android SDK/emulator/device test, Android framework compilation, APK build/install, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
