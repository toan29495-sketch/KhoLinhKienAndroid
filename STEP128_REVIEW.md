# Step128 Review – Photo lifecycle, deletion and safe-retention audit

Date: 2026-11-03

## Baseline and scope
- Continued from the exact Step127 archive after verifying its SHA-256 (`a6a2c304dde94773fc26b830274966d8710374ae58f1bee1f4de13999003f579`) and running `unzip -tq` successfully.
- This is an audit/guardrail step. No production Kotlin, SQL migration, Edge Function, Storage policy, or application behavior was changed. Added a focused regression verifier and this review/progress entry only.

## Confirmed safe behavior
- Deleting a Part snapshots its previous `photo_path` into the immutable `PART_DELETE` Outbox payload and enqueues the operation before removing the local Part row, within one Room transaction.
- Movement history is retained independently of the deleted Part.
- A delete Outbox acknowledgement requires the exact owner/entity/type/in-flight row, confirms the Part has not reappeared, and refuses completion while another unresolved mutation for that entity remains.
- Local pending photo files are deleted only after the exact RPC response has been validated and Room has acknowledged the Outbox row. Deletion is best-effort and is skipped while another unresolved Outbox payload references that local path.
- RPC failure/cancellation does not run the local-photo deletion block. Cloud Storage object deletion is not invoked by the Android client or current Edge Functions.
- Storage has an authenticated owner-folder delete policy plus a restrictive all-operation owner-folder/active-profile guard. The `sync_operations` ledger is not directly accessible to authenticated clients.

## Why cloud garbage collection is deliberately deferred
A safe cloud-photo collector cannot be implemented by simply deleting objects whose path is absent from `parts.photo_path`:
1. `sync_operations.payload` retains immutable operation payloads, including historical photo paths, to support idempotent replay.
2. `inventory_sync_events` is an append-only change feed whose payload snapshots may still be consumed by clients with older cursors.
3. The current repository has no trusted Storage garbage-collection Edge Function, no bounded retention/compaction contract for the operation ledger/change feed, and no live PostgreSQL/Storage environment to validate a collector.
4. A client-side delete can race a newer reference, another device, or an in-flight restore. The existing owner-only Storage delete policy is not itself proof that an object is unreferenced.

Therefore cloud objects that become orphaned may remain in Storage for now. This is an intentional fail-safe, not a completed cleanup feature. Before implementing GC, design and test: ledger replay retention, change-feed cursor expiry/compaction, a server-side candidate queue, final reference checks across current rows and retained replayable events, owner isolation, idempotent deletion, and a dry-run/report mode. The collector must use server credentials in a trusted function, never client service-role credentials.

## Verification
- `tools/verify_step128_photo_lifecycle_retention.py`: 19/19 PASS.
- Step127 photo retry/idempotency: 18/18 PASS.
- Step126 photo read-back: 17/17 PASS.
- Step85 Outbox/photo race: 13/13 PASS.
- Step90 session fencing: 10/10 PASS.
- Step106 logout/in-flight cancellation: 13/13 PASS.
- Step125 deleted-Part history: 20/20 PASS.
- Step49–72 sync: 24/24 PASS + SQLite schema/atomic replacement smoke PASS.

## Not verified
- No live Supabase Storage/RPC, network fault injection, cross-device race, PostgreSQL staging, or migration deployment.
- No Android SDK/emulator/device test, Android framework compilation, APK build/install, or GitHub push.
- No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source.
