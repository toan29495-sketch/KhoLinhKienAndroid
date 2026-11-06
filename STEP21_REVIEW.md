# Step 21 review — post-apply restore cleanup

## Implemented
- Added `InventoryDao.clearCompletedRestoreStagedRows(ownerId, restoreId)`, which deletes staged rows only when the matching owner-scoped journal is already `COMPLETE`.
- Added `RestoreStagingLifecycle.cleanupCompleted(...)`:
  - requires a matching owner and `COMPLETE` journal;
  - verifies the current Room sync cursor equals the journal snapshot cursor;
  - validates the canonical staging directory remains under `filesDir/restore_staging/<restoreId>`;
  - deletes only local staged rows and the local staging directory;
  - retains the COMPLETE journal as an audit/retry marker;
  - is safe to retry after process interruption.
- Wired cleanup after `applyCommittedRestoreSnapshot` in `RestoreCloudSnapshotApplier`. Cleanup is best-effort: if deletion fails after the Room transaction has committed, the restore remains successful and `localCleanupComplete=false` signals that local cleanup must be retried.
- Explicitly does not delete cloud Storage objects, live inventory, movement history, outbox, or sync cursor.

## Static review
- Cleanup cannot run before Room marks the journal COMPLETE.
- The cursor must still equal the committed snapshot cursor before local staging is deleted.
- Owner ID is checked before any deletion.
- Staging path is canonicalized and constrained to the app's restore_staging root.
- COMPLETE journal is retained, so a later retry can recover the directory path after a crash.

## Not verified
- No Gradle/Kotlin compilation or Room compiler run.
- No Room instrumentation/migration tests.
- No PostgreSQL/Supabase execution or Storage test.
- No APK build or device test.
- Cleanup is implemented and wired into the applier component, but the complete restore orchestrator/UI entrypoint is still not wired; the old UI entrypoint remains a release blocker.
