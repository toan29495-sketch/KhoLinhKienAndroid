#!/usr/bin/env python3
"""Step165 shared identity helper and lifecycle safety source checks."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
IDENT=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupIdentity.kt').read_text()
REC=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
WORK=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
MAIN=(ROOT/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
BACK=(ROOT/'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks=[]
def ck(name, ok): checks.append((name,bool(ok)))
ck('shared canonical UUID helper is top-level and internal', 'internal fun isCanonicalOwnerId(value: String)' in IDENT)
ck('canonical UUID helper validates UUID structure and canonical representation', 'UUID.fromString(value).toString().equals(value, ignoreCase = true)' in IDENT)
ck('shared active-owner helper checks authenticated Supabase identity', 'internal fun requireActiveOwner(ownerId: String)' in IDENT and 'currentUserOrNull()?.id' in IDENT and 'if (activeId != ownerId)' in IDENT and 'WeeklyBackupIdentityException' in IDENT)
ck('recovery imports shared top-level identity helpers without private-scope dependency', 'isCanonicalOwnerId(ownerId)' in REC and 'requireActiveOwner(ownerId)' in REC and 'private fun requireActiveOwner' not in REC)
ck('worker resolves shared UUID helper', 'isCanonicalOwnerId(ownerId)' in WORK and 'private fun isCanonicalOwnerId' not in WORK)
ck('worker verifies session owner again through recovery and uploader', 'WeeklyBackupRecovery.recoverOwner' in WORK and 'uploadAndVerify(prepared, backupId)' in WORK)
signout_start=MAIN.index('val signOut: () -> Unit')
signout_end=MAIN.index('if (checkingSession)', signout_start)
signout_block=MAIN[signout_start:signout_end]
ck('logout cancels owner-specific weekly backup work before auth signout', 'WeeklyBackupWorkScheduler.cancel(context, ownerId)' in signout_block and signout_block.index('WeeklyBackupWorkScheduler.cancel(context, ownerId)') < signout_block.index('authRepository.signOut()'))
ck('verified profile lifecycle activates owner-scoped periodic backup', 'WeeklyBackupWorkScheduler.schedule(context, profile.id)' in MAIN)
role_refresh_start=MAIN.find('catch (inactive: InactiveProfileException)', MAIN.find('Refresh the authoritative role/profile'))
role_refresh_end=MAIN.find('catch (error: Exception)', role_refresh_start)
ck('Admin-to-KTV role change does not cancel own warehouse backup schedule', role_refresh_start >= 0 and role_refresh_end > role_refresh_start and 'WeeklyBackupWorkScheduler.cancel' not in MAIN[role_refresh_start:role_refresh_end])
ck('existing BackupManager private helper remains isolated and does not conflict with shared helper', 'private fun isCanonicalOwnerId(value: String)' in BACK)
ck('worker still checks password-change and active profile gates', 'profile.mustChangePassword' in WORK and 'profile.isActive' in WORK)
ck('worker cancellation propagates', 'catch (cancelled: CancellationException)' in WORK and 'throw cancelled' in WORK)
for n,ok in checks: print(f'{"PASS" if ok else "FAIL"}: {n}')
print(f'RESULT: {sum(v for _,v in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(v for _,v in checks) else 1)
