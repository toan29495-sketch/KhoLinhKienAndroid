# Step141 – Restore backend audit and Admin edge guard review (2026-11-03)

## Baseline
- Started from Step140 source ZIP with SHA-256 `cdbaa8ab2c1c3a427bc9a8e6b610fd07f608aa843d41860ccfa48eb35e4d4f81`.
- Supabase project: `ihhxxxqojelbxisreqns`.
- No Gradle, Android Studio, Android SDK, emulator, or device build was used.

## Cloud-photo audit
- `PartPhotoPreview` downloads private cloud photos through `downloadAuthenticated()` into a bounded in-memory byte array.
- It does not write cloud preview bytes to `cacheDir` or `filesDir`.
- Owner-prefixed object paths are validated; absolute paths, backslashes, blank path segments, `.` and `..` are rejected.
- Local pending photos are constrained to app-private storage and read with a 20 MiB preflight + streaming hard limit.
- The temporary byte array is zeroed after decode; coroutine cancellation is propagated.
- `tools/verify_step140_cloud_photo_memory_only.py`: 13/13 PASS.
- `tools/verify_step126_sync_photo_readback.py`: 19/19 PASS.
- `tools/verify_step127_photo_retry_idempotency.py`: 18/18 PASS.
- `tools/verify_step128_photo_lifecycle_retention.py`: 19/19 PASS.
- `tools/verify_step129_server_only_photo_delete.py`: 18/18 PASS.

## Live Supabase restore verification
- Confirmed six required RPCs exist: `begin_inventory_restore`, `stage_inventory_restore_chunk`, `commit_inventory_restore`, `read_inventory_restore_snapshot_chunk`, `verify_inventory_restore_snapshot_chunk`, `cleanup_my_inventory_restore_staging`.
- All six RPCs have EXECUTE for `authenticated`; none have EXECUTE for `anon`.
- Restore staging/session/commit tables have no direct authenticated table grants; they are accessed through owner-bound SECURITY DEFINER RPCs.
- `app_admins` has no authenticated INSERT/UPDATE/DELETE table grants.
- `handle_new_user` and `rls_auto_enable` are no longer directly executable by anon/authenticated clients.
- Transaction-only smoke tests passed for begin/stage, exact chunk replay idempotency, one-active-session quota, atomic commit, idempotent commit retry, snapshot read, payload verification, cross-owner read denial, and stale STAGING cleanup. Each test ended with ROLLBACK.
- Additional transaction-only test passed for preserving movement history when the original part/QR no longer exists (`part_id IS NULL`), publishing the `RESTORE_COMMIT` marker, and rejecting malformed movement arithmetic without damaging the prior live rows. It ended with ROLLBACK.
- Final live counts after rollback: 2 profiles (1 active Admin, 1 active KTV), 1 Admin link, 0 parts, 0 movements, 0 sync operations, 0 sync events, 0 restore sessions, 0 restore chunks, 0 restore commits, and 0 Storage objects.

## Security advisor interpretation
- Four RLS-enabled internal tables have no direct policies. This is intentional default-deny because direct client table grants are revoked; use the checked RPCs.
- Fourteen authenticated SECURITY DEFINER warnings are for the app's intentional checked RPCs/helper predicates. `anon` has no EXECUTE on these checked functions.
- Leaked-password protection remains disabled because the current Admin/KTV onboarding still uses the shared temporary password `000000`. Do not enable until onboarding uses a generated/unique temporary credential or a safe invitation flow.
- Performance Advisor currently reports unused indexes only. This is expected for an empty test database; re-evaluate after real inventory workload.

## Edge Function follow-up
- Live `admin-users` remains ACTIVE at version 3. It checks `profiles.role=admin` and `is_active=true` but does not independently verify the `app_admins` link.
- A version-4 candidate that adds this link check is stored separately at `supabase/functions/admin-users-v4-candidate/index.ts`.
- Deployment of v4 was attempted but blocked by the tool safety guard; it is NOT deployed. The active `supabase/functions/admin-users/index.ts` is deliberately kept at the exact v3 source to avoid local/live drift.

## Migration source reconciliation
- Live migration history contains seven Step138 and nine Step139 migrations.
- The Step140 archive contains the seven Step138 active migration files and a Step139 history document, but not the exact nine Step139 SQL files in the active migration folder.
- Do not replay `migrations_pending_reconciliation` or run a blanket migration push. Before the next GitHub source handoff, reconstruct/export the exact Step139 migration SQL from the live definitions and reconcile it into source history.

## Not claimed
- No Android framework compile, Room/Compose/WorkManager runtime, emulator/device test, APK build/install, or GitHub push.
- No live restore was executed; all restore tests used synthetic UUIDs in rolled-back transactions.
