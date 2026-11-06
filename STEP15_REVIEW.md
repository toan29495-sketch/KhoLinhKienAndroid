# Step 15 review — server-side atomic commit client

## Added
- `RestoreCloudCommitter` authenticates the current Supabase user and requires it to equal the restore owner.
- Only accepts `CLOUD_STAGED`, `COMMIT_UNKNOWN`, or `COMMIT_CONFIRMED` journals.
- Persists `COMMIT_UNKNOWN` before issuing `commit_inventory_restore`, so a process death or lost response can be resolved by replaying the same idempotent RPC.
- Validates response status (`committed` / `already_committed`), restore ID, manifest SHA-256, positive snapshot cursor, and both record counts against the local journal.
- Only after all response fields match does it persist `COMMIT_CONFIRMED` and the snapshot cursor.
- The class does not mutate local `parts`, `movements`, outbox, or sync cursors.

## Important boundary
This is the Android caller for the already-authored SQL atomic commit RPC. It is not yet wired into the restore UI/worker. It does not fetch snapshot chunks or reconcile Room. Do not claim end-to-end safe restore yet.

## Static review performed
- Compared RPC name/parameter and response keys against migration `202611020006_restore_commit_and_snapshot_read.sql`.
- Confirmed the SQL returns `committed` or `already_committed`, restore_id, manifest_sha256, snapshot_cursor, part_count, movement_count.
- Confirmed `COMMIT_UNKNOWN` remains resumable and active restore guard will continue blocking sync while unresolved.

## Not performed
No Gradle, Kotlin compilation, Room instrumentation, PostgreSQL execution, Supabase deployment, APK build, device test, or GitHub push.
