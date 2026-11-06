# Step127 – Photo upload retry/idempotency and account-boundary review (2026-11-03)

## Baseline and scope
- Continued from the exact Step126 archive after SHA-256 verification (`e0233885bec973e0b0353b9d67c0e8b86dba5568638eacb4cf23ee7bd391af9a`) and ZIP integrity validation.
- Audited normal KTV photo upload across Storage upload, authenticated read-back, `apply_inventory_operation`, Room Outbox acknowledgement, retry after transport failure/cancellation, and local pending-file cleanup.

## Findings and change
- Deterministic Storage object paths are derived from owner ID, entity sync ID and immutable Outbox operation UUID. A retry of the same Outbox row therefore targets the same object.
- The SQL operation ledger serializes duplicate operation UUIDs, requires the exact original payload, and returns the cached result after an already-processed operation. A lost RPC response can be retried without creating a duplicate inventory mutation; a changed payload under the same operation UUID is rejected.
- Local pending photo files are uniquely named in an owner-private directory. The sender retains the file after upload/RPC failure and only attempts best-effort deletion after the exact Room Outbox acknowledgement. It first checks for any other unresolved Outbox payload referencing that path.
- No client-side cloud Storage object deletion is attempted. This is intentional: an ambiguous RPC result or a newer part reference must not be turned into accidental cloud-photo loss. Unreferenced objects from permanently rejected operations may remain as storage orphans; safe server-side garbage collection requires a separate reference-aware design and is not introduced here.
- Found a narrow session-fencing gap: after hashing a pending image (up to 20 MiB), the shared Supabase session could change before the Storage upload call. Added `requireActiveOwner(ownerId)` immediately before the upload network boundary. Existing checks after upload, after read-back, and before RPC/acknowledgement remain.
- Updated the Step90 verifier's assertion to test the actual ordering (post-hash owner check before upload), rather than requiring adjacency that became stale after Step126's hashing/read-back implementation.

## Verification
- `tools/verify_step127_photo_retry_idempotency.py`: 18/18 PASS, including deterministic retry path, exact operation UUID/payload, cached RPC result, rejection of changed payload, delayed local deletion, reference guard, and account recheck immediately before upload.
- `tools/verify_step126_sync_photo_readback.py`: 17/17 PASS.
- `tools/verify_step85_photo_outbox_race.py`: 13/13 PASS.
- `tools/verify_step90_session_fencing.py`: 10/10 PASS.
- `tools/verify_step106_logout_inflight_fence.py`: 13/13 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS + SQLite schema/atomic replacement smoke PASS.
- `tools/verify_step113_restore_cancellation_resume.py`: 14/14 PASS.
- `tools/verify_step125_restore_deleted_part_history.py`: 20/20 PASS.
- Kotlin/JVM `PhotoIntegritySmoke`: 7/7 PASS.
- Python tooling `compileall`: PASS.

## Limitations / not performed
- No live Supabase Storage/RPC retry, network-loss, account-switch, or concurrent-client test; no PostgreSQL staging execution or SQL deployment.
- No Android SDK/emulator/device available; no Android framework compile, APK build/install, or physical-device test.
- No GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source.
