# Sync cursor commit-order contract (Step130)

## Problem

`inventory_sync_events.event_id` is a global `bigserial` cursor. PostgreSQL sequence
allocation is not transactional and does not imply commit order. Without coordination,
transaction A can reserve event ID 100 and remain uncommitted; transaction B can reserve
101 and commit; a pull can see 101, advance its cursor to 101, and permanently miss A
when A later commits with ID 100. This affects the per-owner feed and, more critically,
the Admin feed whose cursor spans every owner.

## Guard

Migration `202611030015_serialize_sync_event_commit_order.sql` removes the column
sequence default and installs a `BEFORE INSERT` trigger. The trigger takes a single
transaction-scoped advisory lock, then obtains and assigns the next sequence value.
The lock remains held until the writer transaction commits or rolls back. Therefore,
no later writer can receive a larger visible event ID before the earlier writer's
transaction outcome is known. The trigger covers every insert source at the table
boundary, including normal inventory RPCs and RESTORE_COMMIT, without needing each
RPC implementation to remember a separate lock call.

The lock is global, not per owner, because the Admin feed uses one global cursor. It
serializes only transactions publishing change-feed events; PostgreSQL execution and
lock-wait performance must be measured in staging before rollout at scale.

## Client paging

`SyncPullWorker` applies each event before persisting that event's cursor. A replay after
process death is idempotent for parts (server-version/tombstone checks) and movements
(immutable sync ID equality). If a full final page is returned at the per-run page cap,
the worker returns `Result.retry()` rather than success, so a backlog larger than one
run is drained without waiting solely for the 15-minute periodic schedule. A full page
that happened to be the final page can cause one harmless empty follow-up run.

## Operational requirements

- Deploy this migration before relying on the corrected cursor contract.
- Confirm the trigger is enabled and the column default is absent in hosted PostgreSQL.
- Exercise concurrent commits where the first transaction pauses after event insertion;
  verify the second writer blocks and cannot publish a higher event ID first.
- Exercise per-owner and Admin pulls across the same concurrent workload and confirm
  every committed event is observed exactly once logically (replays may occur, but are
  idempotent).
- Measure advisory-lock wait time and throughput with realistic concurrent KTV load.
- No change-feed pruning is permitted without a cursor-expiry/resnapshot protocol.
