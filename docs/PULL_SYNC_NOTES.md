# Android pull sync notes

## Implemented in working copy
- Room v6 stores one monotonic `lastEventId` cursor per owner.
- `SyncPullWorker` calls `pull_inventory_changes(p_after_event_id, p_limit)` and processes events in order.
- PART snapshots are upserted by cloud UUID; tombstones remove only the matching owner's local row.
- MOVEMENT snapshots are inserted idempotently using the cloud movement UUID as `syncId`.
- Cursor is saved after each successfully applied event. Replaying an event after a crash is safe because PART is upserted and MOVEMENT uses `IGNORE` on unique `syncId`.
- A PART event is not applied while that part has a pending/failed/in-flight local outbox operation. The cursor does not pass the blocked event; WorkManager retries after local send can finish.

## Known limitations / not yet enabled for production
- Photos received from cloud are not downloaded yet. Existing local photo files are preserved; a new remote part currently appears without its image until Storage download support is implemented.
- No server-version conflict UI or metadata merge yet. Outbox presence prevents overwriting local pending changes, but concurrent edits made on two offline devices still need explicit conflict resolution.
- Hard-delete local application of cloud tombstones removes the part row; historical movements remain. A local tombstone table and restore reconciliation still need review.
- Supabase migration and RLS/Storage policies have not been run against the live project.
- Gradle compile could not start because Gradle 8.9 distribution host was unreachable in this environment.
