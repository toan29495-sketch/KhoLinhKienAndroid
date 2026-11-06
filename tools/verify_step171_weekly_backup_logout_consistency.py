#!/usr/bin/env python3
"""Step171 checks inactive-session logout and owner-scoped weekly work retirement."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
AUTH = (ROOT / 'app/src/main/java/vn/kholinhkien/AuthRepository.kt').read_text()
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
IDENT = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupIdentity.kt').read_text()
checks = {
    'repository exposes current session owner for pre-validation capture': 'fun currentUserId(): String? = client.auth.currentUserOrNull()?.id' in AUTH,
    'session owner is captured before profile validation can sign out': MAIN.index('val restoringOwnerId = authRepository.currentUserId().orEmpty()') < MAIN.index('authRepository.getCurrentProfile()'),
    'inactive restored session cancels only a canonical captured owner': 'if (isCanonicalOwnerId(restoringOwnerId)) {' in MAIN and 'WeeklyBackupWorkScheduler.cancel(context, restoringOwnerId)' in MAIN,
    'explicit logout still cancels weekly work before auth signout': MAIN[MAIN.index('val signOut: () -> Unit'):MAIN.index('if (checkingSession)')].index('WeeklyBackupWorkScheduler.cancel(context, ownerId)') < MAIN[MAIN.index('val signOut: () -> Unit'):MAIN.index('if (checkingSession)')].index('authRepository.signOut()'),
    'session-recovery signout resolves owner from live session when profile is absent': 'val ownerId = authRepository.currentUserId().orEmpty()' in MAIN and 'if (isCanonicalOwnerId(ownerId)) {' in MAIN and 'WeeklyBackupWorkScheduler.cancel(context, ownerId)' in MAIN,
    'worker retires only its input owner schedule on inactive profile': 'catch (_: InactiveProfileException) {\n            // Profile validation may sign out the shared Supabase session.\n            // Retire only this owner\'s periodic work; never cancel another owner.\n            WeeklyBackupWorkScheduler.cancel(applicationContext, ownerId)' in WORK,
    'worker validates canonical owner before any session or schedule action': 'if (!isCanonicalOwnerId(ownerId)) return Result.failure()' in WORK,
    'scheduler unique work identity includes exact owner ID': 'private fun uniqueName(ownerId: String) = "inventory-weekly-backup-$ownerId"' in WORK,
    'scheduler cancel rejects malformed owner IDs': 'fun cancel(context: Context, ownerId: String) {\n        if (!isCanonicalOwnerId(ownerId)) return' in WORK,
    'shared identity helper remains fail-closed against a changed session': 'if (activeId != ownerId)' in IDENT and 'WeeklyBackupIdentityException' in IDENT,
    '30-day backup is scheduled only from the validated profile lifecycle': 'WeeklyBackupWorkScheduler.schedule(context, profile.id)' in MAIN and MAIN.index('if (profile.mustChangePassword)') < MAIN.index('WeeklyBackupWorkScheduler.schedule(context, profile.id)'),
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
