# Step195 — Durable staging integrity and recovery eligibility (03/11/2026)

## Source base
- Exact source base: Step194 archive `KhoLinhKienAndroid-step194-low-storage-staging-safety-20261103.zip` (SHA-256 to be recorded in delivery manifest).
- Incremental changes only; no older source archive substituted.

## Changes
- `RestoreStagingLifecycle.inspect()` now reports photo readiness only when the staged source rows pass structural validation and every referenced photo is present and safe.
- `canResumeStaged()` now requires the exact owner-scoped durable staging directory, complete source row integrity, and valid referenced photo files; aggregate row counts alone no longer qualify a STAGED restore as resumable.
- Structural validation checks exact row counts, owner/restore/entity identity, zero-based contiguous chunk indexes, zero-based contiguous row indexes within each chunk, a maximum of 200 rows per chunk, and nonblank payloads.
- Referenced photo paths are constrained beneath the canonical `photos` directory; files must exist, be nonempty, and use a supported image extension. Unreferenced files are not treated as proof of a valid restore.
- `RestoreCloudPayloadPreparer` now reuses the structural source-row validator before uploading photos or deriving cloud payload rows.
- Validation is read-only. It does not repair, delete, or mutate staged/live data. Invalid staging remains ineligible and requires controlled re-import/inspection.

## Verification
- `verify_step195_staging_integrity.py`: 14/14 PASS.
- Regression Step190–Step195: Step190 15/15, Step191 16/16, Step192 10/10, Step193 16/16, Step194 17/17, Step195 14/14 = 88/88 checks across 6 scripts.
- Compatibility Step112/115/116/117/150: 63/63 PASS.
- `python -m compileall -q tools`: PASS.

## Limits / not performed
- Static source-contract checks only. No Room database corruption injection, missing-photo runtime test, process death, filesystem fault injection, concurrent worker/coordinator test, or Android device execution.
- No Kotlin compilation, APK build/install, or phone test. Gradle was not run, per instruction.
- No Supabase RPC/Storage operation, migration, remote/local business data mutation, restore commit, GitHub push, or destructive cleanup.
- This improves restore eligibility checks but does not establish end-to-end restore readiness.
