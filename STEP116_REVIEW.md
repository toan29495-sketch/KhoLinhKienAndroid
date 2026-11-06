# Step116 – Strict owner identity and legacy ZIP-temp isolation

Date: 2026-11-03  
Baseline: Step115 checkpoint `KhoLinhKienAndroid-step115-zip-temp-account-isolation-20261103.zip`.

## Scope
Close the remaining cross-account cleanup ambiguity in ZIP extraction cache handling and validate owner identity before any cleanup or filesystem side effect.

## Findings
- Step115 accepted any 36-character string composed of hexadecimal characters and hyphens as an owner ID. This was narrower than arbitrary input but did not enforce the canonical UUID group layout or a UUID round trip.
- Step115 still allowed any KTV's stale cleanup sweep to reclaim old unscoped `zip_restore_<UUID>` folders created by pre-Step115 versions. Those folder names contain no authenticated owner identity, so ownership cannot be proven.

## Changes
- Validate owner ID at the very start of `restore()`, before cleanup, StatFs, or directory creation.
- Add strict canonical UUID layout validation (8-4-4-4-12 hexadecimal groups) and `UUID.fromString(...).toString()` round-trip validation.
- Apply the same validation inside `cleanupStaleTemporaryStages()` so it cannot be called with malformed identity.
- Cleanup now enumerates and deletes only direct-child folders with the exact active owner's `zip_restore_<ownerId>_` prefix and age >= 24 hours.
- Stop automatically deleting legacy unscoped ZIP temp folders. Their owner cannot be established safely; they remain ordinary app cache and may be reclaimed by Android's cache management rather than by another KTV's restore action.
- Preserve immediate `finally` cleanup, canonical direct-child checks, 32 MiB preflight, 300 MiB aggregate ZIP cap, per-entry/photo limits, SHA-256 verification, durable owner-scoped restore staging, and cancellation/resume behavior.
- Updated Step112/115 source verifiers to assert the strengthened current invariant; historical review documents remain unchanged.

## Verification
- `tools/verify_step116_restore_temp_legacy_isolation.py`: 11/11 PASS.
- `tools/verify_step115_zip_temp_account_isolation.py`: 10/10 PASS.
- `tools/verify_step112_restore_temp_cleanup.py`: 10/10 PASS.
- `tools/verify_step113_restore_cancellation_resume.py`: 14/14 PASS.
- `tools/verify_step114_restore_account_isolation.py`: 11/11 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS + SQLite schema/atomic replacement smoke PASS.
- `python3 -m compileall -q tools`: PASS.

## Limitations
- Static/source verification only. No Android compilation, APK build/install, physical device, account-switch, process-kill, or cache-pressure runtime test.
- No live Supabase/PostgreSQL staging, SQL or Edge Function deployment, or GitHub push.
- Legacy unscoped cache directories are deliberately not reclaimed by KTV-triggered cleanup because ownership is unknowable. Android may evict app cache; a future safe migration would require a trustworthy owner marker written by the old version, which these legacy folders do not contain.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
