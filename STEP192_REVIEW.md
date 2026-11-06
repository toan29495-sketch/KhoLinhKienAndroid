# Step192 — Restore resume owner-authentication fence (03/11/2026)

## Source base
- Continued directly from the Step191 working tree at `/mnt/data/work190/KhoLinhKienAndroid`.
- Step191 archive SHA-256: `fc0cd98a3f7f1f2e749f2f9b026f30f0f2f8401aa4972aa4e44791448828b220`.
- No older source archive was unpacked or substituted.

## Finding and change
- Audit found that `RestoreCloudCoordinator.resumeLocked()` read a journal and could enter the `COMPLETE` cleanup branch without first confirming that the active Supabase Auth UID still matched the journal owner. The UI normally supplies the current profile ID, but the coordinator itself must enforce this security boundary, including stale-screen/account-switch races.
- Added one exact Auth UID guard and applied it at both import entry points and the resume boundary. Rechecked after journal lookup and before the `COMPLETE` cleanup path.
- The pending-outbox check is now bracketed by owner checks so a logout/account switch during the local DAO read stops the operation.
- Existing lower-level phase guards remain: photo preparation/upload, Cloud chunk staging, commit, snapshot download, and Room apply each validate the owner at their own boundaries. Room live inventory remains gated behind `SNAPSHOT_DOWNLOADED` and the existing atomic DAO apply.
- No restore protocol states, SQL, Room schema, or data formats changed.

## Verification
- `tools/verify_step192_restore_resume_owner_guard.py`: 10/10 PASS.
- `tools/verify_step191_cloud_inbox_restore_confirmation.py`: 16/16 PASS.
- `tools/verify_step190_durable_cloud_restore_inbox.py`: 15/15 PASS.
- Targeted regression Steps158–192: 35/35 verifier scripts PASS, 577 counted assertions PASS.
- `python -m compileall -q tools`: PASS.

## Not verified / safety boundary
- These are source-contract/static checks, not runtime concurrency tests. No actual logout/account-switch race, process death, network loss, low-storage fault injection, Storage upload/download, remote commit, or Room apply was executed on Android.
- No Kotlin compilation, Android build, APK build, install, or phone test. Gradle intentionally skipped per instruction.
- No Supabase migration, RPC, Storage object, profile, inventory, movement, outbox, restore journal, or other remote/local business data was changed by Step192.
- No GitHub push.
