# Step191 Review — Recoverable Cloud inbox discovery and explicit restore confirmation

Date: 03/11/2026

## Source base
- Exact input: `KhoLinhKienAndroid-step190-durable-cloud-restore-inbox-20261103.zip`
- Input SHA-256: `ae4577c81d43ed221c19f10f590b7687935cf8e0aff53d5df1a5f0de071851e5`
- Continued from the Step190 source tree; no older ZIP was substituted.

## Changes
1. Added `WeeklyBackupRestoreInbox.listVerified(ownerId)` to rediscover completed inbox archives after process death/app restart. It validates the current Supabase Auth UID before and after scanning, canonical owner folder, UUID backup ID, owner metadata, exact storage path, allowed backup kind, file size, SHA-256 and ZIP archive structure. Partial files and orphan ZIPs are not presented as selectable items.
2. Added a read-only UI action to scan the current account's durable inbox on demand. Inbox rows are owner-keyed Compose state and are not inherited across account switches.
3. Added an explicit destructive confirmation dialog describing that the selected backup replaces the current account's warehouse and history. Cancel leaves data unchanged.
4. Added a `ZipRestore` File overload that accepts only a canonical ZIP directly inside the current owner's private inbox. The existing document-URI restore path remains unchanged.
5. Added `RestoreCloudCoordinator.importAndRestore(File, ownerId)`. Before staging it rechecks current Auth UID, absence of pending outbox operations, canonical inbox path and the archive's presence in the freshly verified inbox listing. It then uses the existing resumable cloud restore coordinator; Room is still applied only after remote commit and snapshot verification.
6. UI callback rechecks current Auth UID and absence of an active restore before invoking the coordinator. Failures are surfaced to the user; no automatic deletion of inbox archives occurs after restore.

## Verification
- `verify_step191_cloud_inbox_restore_confirmation.py`: 16/16 PASS.
- `verify_step190_durable_cloud_restore_inbox.py`: 15/15 PASS.
- Targeted Step158–Step191 backup/restore regression: 34/34 verifier scripts PASS, 567 counted checks PASS.
- `python -m compileall -q tools`: PASS.
- A broader Step150–Step191 verifier sweep exceeded the execution timeout; it is not counted as passing.

## Not verified / remaining gates
- No Kotlin compilation, Android build, APK build, install, or physical-device test. Gradle intentionally skipped per user instruction.
- No live Cloud backup was downloaded/restored through Android UI; Storage and remote restore end-to-end remain untested on device.
- No Supabase SQL migration or remote data mutation was performed in this step.

## Data safety
- The restore operation is only initiated after an explicit confirmation click.
- The existing coordinator's no-pending-outbox, owner isolation, remote staging/commit, snapshot verification and final Room apply gates remain in force.
- Inbox archives are retained; this step does not delete Cloud backups or existing user data.
