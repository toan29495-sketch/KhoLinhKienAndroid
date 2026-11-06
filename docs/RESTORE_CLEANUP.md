# Restore cleanup protocol

Cleanup is strictly post-apply and local-only.

1. `InventoryDao.applyCommittedRestoreSnapshot` validates the complete snapshot, replaces only the authenticated owner's parts/movements, writes the owner cursor, and marks the journal `COMPLETE` in one Room transaction.
2. Only after that transaction returns does `RestoreStagingLifecycle.cleanupCompleted` run.
3. Cleanup verifies owner, `COMPLETE` journal, snapshot cursor, and canonical directory under `filesDir/restore_staging/<restoreId>`.
4. It deletes local `restore_staged_rows` for that completed restore and then the local staging directory (including temporary ZIP photos and upload checkpoint).
5. It retains the completed journal for audit and crash recovery. If the process stops or file deletion fails, cleanup can be called again using the retained journal.

Cleanup never deletes Supabase Storage objects. It never modifies live parts, movements, outbox, or sync cursor. A failed cleanup does not undo or misreport the already committed Room restore; caller receives `localCleanupComplete=false` and should retry cleanup later.

## Temporary ZIP extraction cleanup (Step112)

- Each archive is extracted into a uniquely named `cacheDir/zip_restore_<UUID>` folder.
- A restore attempt first removes only direct-child folders with that prefix whose age is at least 24 hours and whose canonical parent is exactly the app cache root.
- A minimum 32 MiB available-cache preflight is checked before creating a new extraction folder. This is only a start threshold, not a guarantee that the full archive will fit; actual 300 MiB aggregate and per-entry limits remain authoritative.
- The active temporary folder is deleted best-effort in `finally`. If the process is killed or deletion fails, a later restore can reclaim it after the age threshold.
- This sweep never touches durable `filesDir/restore_staging`, Room restore journals/staged rows, live inventory, Outbox, or cloud Storage.
