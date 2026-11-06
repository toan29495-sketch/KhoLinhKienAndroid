# Cloud photo retention and garbage-collection safety contract

Status: design contract only; no collector is implemented or deployed.

## Current behavior

- The `part-images` bucket is private and object paths are owner-prefixed.
- Authenticated KTV/Admin clients may read, upload and update only within their own owner folder while the profile is active.
- Step129 removes authenticated/anonymous client DELETE access for `part-images`. A restrictive Storage policy denies DELETE for this bucket even if another permissive policy is added later.
- No Android client, current Edge Function, or SQL migration deletes cloud photo objects.
- No cloud object is deleted by this contract or by Step129.

## Why an object missing from `parts.photo_path` is not sufficient evidence

A photo path can remain in:

1. Current `parts.photo_path` values.
2. Immutable `sync_operations.payload` and its cached `result`, which support idempotent retry and replay.
3. Append-only `inventory_sync_events.payload`, which may still be needed by devices with older cursors.
4. Restore staging/session payloads and restore commit/retry workflows.
5. In-flight Outbox uploads or a request whose RPC result is ambiguous.

Therefore, no age-only rule and no `NOT EXISTS(parts.photo_path)` query is a safe deletion rule.

## Preconditions before a future collector may delete

1. Define explicit retention/compaction for operation-ledger rows and change-feed events, including a server-enforced minimum supported cursor and a client resnapshot path for expired cursors.
2. Define restore-session retention and prove no active or resumable restore can still publish the candidate path.
3. Record a candidate only after a conservative grace period longer than the maximum supported retry/offline window; candidates are advisory, not authorization to delete.
4. Run collection only in a trusted server-side function using server credentials held outside clients. Never embed a service-role key in the APK or issue it to KTV/Admin clients.
5. Before deletion, lock/recheck the candidate and search all live parts, retained operation payloads/results, retained change-feed payloads, restore staging, and any other durable reference. Recheck owner and bucket/path prefix.
6. Use an idempotent state machine (`DISCOVERED` → `QUARANTINED` → `RECHECKED` → `DELETED`) with a quarantine delay and an auditable reason. A newly observed reference cancels quarantine.
7. Provide dry-run/report mode first. A deletion failure remains retryable; a deleted object is never treated as recoverable by retrying an old mutation without restoring the object first.
8. Enforce per-owner quotas and bounded batches; never enumerate or delete across owner boundaries.

## Step129 scope

Step129 implements only the Storage authorization guard that prevents client-side deletes. It does not implement a candidate table, retention/compaction, Edge Function, scheduler, or actual deletion. Those require a separately reviewed server contract and live staging validation.
