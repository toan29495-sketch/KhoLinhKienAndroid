# Step 22 review — resumable cloud restore coordinator

## Implemented
- Added `RestoreCloudCoordinator` as the single orchestration boundary for ZIP import and resume by exact `restoreId`.
- Sequence: ZIP preflight + durable Room staging → photo upload/read-back + payload mapping → idempotent cloud session/chunk staging → idempotent cloud commit → snapshot download + PostgreSQL-side checksum verification → atomic owner-scoped Room apply → local staging cleanup.
- Resume dispatch follows the persisted `RestoreJournal.status`; it does not restart completed earlier phases unnecessarily.
- Checks the owner's unsent outbox before ZIP staging, before cloud commit, and before Room apply. This is required because the active-restore guard pauses ordinary sync. It refuses to proceed rather than silently discarding or stranding pending operations.
- A `COMPLETE` journal can be passed to `resume` to retry local cleanup only. The COMPLETE journal itself is retained.
- The coordinator never directly edits live inventory. The only live replacement remains `RestoreCloudSnapshotApplier` / the DAO transaction.

## Not yet done
- `MainActivity` still calls the old `ZipRestore.restore` entrypoint for ZIP and retains the legacy JSON restore path. The new coordinator is not yet wired to UI; do not present cloud restore as user-enabled yet.
- No UI for listing/resuming an interrupted restore or displaying phase progress.
- The coordinator is source-added only; no Gradle compile/type-check or tests were run.
- SQL migrations/RPCs are not deployed or executed; no Supabase Storage/RPC integration test.
- No APK build, device test, or GitHub push.

## Safety notes
- Outbox check before starting avoids creating an active restore while unsent operations already exist.
- If new local writes create outbox entries during a restore, the final pre-apply check blocks Room replacement. The user must resolve this state deliberately; the coordinator does not clear outbox.
- Network failures retain journal state and can be retried with the same `restoreId`.
