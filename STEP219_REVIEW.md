# Step219 Review — Restore RPC JSON response normalization

## Scope
- Fix unsafe assumptions about Supabase RPC response JSON shape in restore commit, snapshot read/verification, and cloud backup catalog.
- Preserve restore idempotency and owner-scoped journal semantics.

## Changes
- Added `RestoreRpcJson` to accept a direct `JsonElement`, raw JSON text, or a single JSON-string wrapper around an object/array.
- Replaced direct `.jsonObject` assumptions in restore RPC response handling.
- Added safe array/object shape checks and operation-specific diagnostics.
- Added `verify_step219_restore_rpc_json_shape.py`.

## Verification
- Step219 source-contract verifier: 11/11 PASS.
- Backup/restore regression sweep: 53/53 PASS.
- Python compileall for `tools`: PASS.
- Kotlin/Gradle compile: BLOCKED; Gradle 8.9 download failed because `services.gradle.org` could not be resolved.
- APK build and device restore test: NOT RUN / NOT VERIFIED.
- Live Supabase RPC: NOT CALLED.

## Data safety
- No live inventory, movement, outbox, sync cursor, Auth, Storage, Supabase schema, or server data was modified.
- Existing `COMMIT_UNKNOWN` retry remains bound to the same restoreId.
