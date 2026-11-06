# STEP185 — Uninstall/reinstall and remote-backup recovery safety

Date: 03/11/2026

## Scope
Audit the exact Step184 source ZIP for Android uninstall, app-data clear, reinstall, lost Room journal, lost app-private staging ZIP, and the distinction between remote PENDING and VERIFIED backups. This step intentionally makes no production Kotlin, Room schema, SQL, Supabase, Storage, account, or data changes.

## Findings (source verified)
1. The resumable TUS identity is locally anchored: Room journal carries owner + backupId + archive path + exact size/SHA-256, and the app-private staging ZIP supplies the exact bytes. `SupabaseWeeklyBackupUploader.uploadAndVerify` refuses to proceed without that local journal and staged archive.
2. Android uninstall/app-data clear removes the app's private database, private staging ZIP, WorkManager state, and app-private preferences. Therefore a partially uploaded TUS transfer cannot be safely resumed after uninstall: its local archive/fingerprint and journal are gone. The app must not invent the old backupId or rebuild different bytes under it.
3. If a new installation has no active local journal, `WeeklyBackupWorker` currently creates a new UUID. This is a new backup attempt, not recovery of an older Cloud upload. It can leave an older remote PENDING object orphaned; it does not overwrite it because the backup path is identity-scoped and TUS upsert is false.
4. A remote object or metadata row alone is not success. The existing upload path accepts only PENDING/VERIFIED registration responses, downloads the stored object and checks exact byte size + SHA-256, then calls completion (or reconciles an already VERIFIED registration) before committing local Room VERIFIED.
5. The SQL candidate contains `list_my_inventory_backups()` with owner-scoped metadata including status, path, size, SHA-256 and verified timestamp. It remains a candidate migration, not proof that the RPC is deployed or available. Step186 adds an isolated read-only adapter but does not wire it into UI/Worker or invoke it automatically; remote inventory/restore reconciliation is not yet available to users.
6. `WeeklyBackupWorkScheduler.schedule` has no production call site outside its declaration; weekly scheduling remains inactive. A reinstall therefore does not silently resume a weekly schedule from this source state.

## Reinstall recovery matrix
- Local journal + local ZIP exist; remote PENDING: resume only same backupId/path/bytes and re-verify.
- Local journal + local ZIP exist; remote VERIFIED: authenticated readback exact size/SHA-256, then reconcile Room VERIFIED and clean local ZIP.
- Local journal exists but ZIP is unavailable: Step184 retry/preserve behavior; never rebuild under same backupId.
- No local journal/ZIP after uninstall; remote PENDING: cannot resume TUS. Preserve remote metadata/object; do not mark success, delete, or overwrite. Future owner-authenticated catalog flow must classify as incomplete/orphan.
- No local journal/ZIP after uninstall; remote VERIFIED: may be a recoverable backup, but current Android source has no remote listing/restore selection flow. Do not claim restored/verified locally until a future implementation fetches owner-scoped metadata, authenticates download, validates ZIP owner + structure + size + SHA-256, and only then offers restore.
- Remote listing unavailable / RPC not deployed / auth failure: show unavailable/unknown, not “no backups” and not “backup successful”.

## Required next implementation before relying on reinstall recovery
1. Confirm/deploy the owner-scoped listing RPC through an explicitly approved backend migration process; this step did not deploy it.
2. Add an Android remote-backup catalog using authenticated owner-only listing. Distinguish PENDING, VERIFIED, deleted/unavailable and unknown; never infer from Storage object existence alone.
3. For VERIFIED entries, download to bounded temporary storage and verify metadata, owner, archive structure, size and SHA-256 before exposing restore.
4. Restore transactionally with pre-restore snapshot/rollback, owner fences and idempotent recovery; do not overwrite current data without explicit user confirmation.
5. Add an explicit “reinstall recovery” screen and a post-reinstall test matrix (same account, other account, offline, PENDING, VERIFIED, corrupt object, expired session, low storage).

## Verification
- `tools/verify_step185_reinstall_recovery_safety.py`: 15/15 PASS.
- Regression Steps158–185: 28 verifier scripts, 459/459 checks PASS.
- Python `compileall` for verifier tools: PASS; ZIP integrity: PASS.
- No production code changes; no SQL applied; no Supabase RPC/network calls; no Storage object reads/writes/deletes.
- No Gradle, Kotlin compile, APK build/install, device test, real uninstall/reinstall, WorkManager runtime or TUS test.
