# Step173 Review — Weekly backup server-VERIFIED/local-journal reconciliation

## Scope
Close the crash window where the server has committed a backup as `VERIFIED`, but Android dies before Room records the local journal as `VERIFIED`.

## Finding and change
- The existing retry path keeps the same local `UPLOADING` journal, backup ID, metadata, and durable staged ZIP.
- Retry calls `begin_inventory_backup` again. In the backend candidate, that function returns `VERIFIED` only for the authenticated owner and only when the stored backup metadata exactly matches the retry request.
- The client still performs an authenticated Storage download and verifies exact archive size and SHA-256 before trusting the returned `VERIFIED` state.
- If registration is already `VERIFIED`, the client now skips a redundant `complete_inventory_backup` RPC and reconciles Room to `VERIFIED` under the same owner + backup ID. If registration is `PENDING`, the existing completion RPC path remains unchanged.
- Durable staging is deleted only after the local journal transition succeeds. Temporary verification download remains disposable in `finally`.
- Cancellation and active-owner checks remain immediately before the local commit. The staged ZIP path and bytes are not rewritten, preserving the TUS fingerprint.
- Updated the Step172 verifier for the conditional completion branch and added `verify_step173_weekly_backup_verified_reconciliation.py`.

## Verification
- Step173 verifier: 12/12 PASS.
- Step158–173 regression: 16 verifier scripts, 280/280 checks PASS.
- Python compileall: PASS.
- Output ZIP integrity and SHA-256: checked after packaging.

## Not verified / not done
- No live Supabase RPC, Storage download, or backend candidate execution.
- No Android force-stop/reboot/WorkManager runtime test.
- No Kotlin/Android compilation, APK build/install, or physical-device test.
- No TUS, RLS, or staging integration test.
- No schedule activation/enqueue, schema/backend mutation, remote object operation, account/profile/inventory/history mutation, or GitHub push.
- No Gradle used, per explicit instruction.

## Safety
- No Room schema migration; Room remains v14.
- No staged ZIP path/bytes or TUS fingerprint rewrite.
- No remote object deletion or server-side mutation.
