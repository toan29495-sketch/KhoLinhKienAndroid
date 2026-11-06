#!/usr/bin/env python3
"""Step209 source-contract checks for 30-day session age and backup cadence."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
POLICY = (ROOT / 'app/src/main/java/vn/kholinhkien/SessionExpiryPolicy.kt').read_text()
LOGIN = (ROOT / 'app/src/main/java/vn/kholinhkien/LoginScreen.kt').read_text()
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
checks = {
    'session age is fixed at thirty days': 'MAX_SESSION_AGE_DAYS = 30L' in POLICY,
    'session start timestamp is stored separately per canonical owner': 'KEY_PREFIX + ownerId' in POLICY and 'isCanonicalOwnerId(ownerId)' in POLICY,
    'successful sign-in persists timestamp before profile validation': LOGIN.index('SessionExpiryPolicy.markAuthenticated(context, it)') < LOGIN.index('repository.getCurrentProfile()') and '.commit()' in POLICY,
    'missing or future timestamp fails closed': 'startedAt <= 0L || startedAt > nowMs' in POLICY,
    'expiry check uses absolute elapsed age rather than token refresh': 'nowMs - startedAt >= MAX_SESSION_AGE_MS' in POLICY,
    'cold-start restore enforces session age before profile access': MAIN.index('SessionExpiryPolicy.isExpired(context, restoringOwnerId)') < MAIN.index('authRepository.getCurrentProfile()'),
    'foreground return enforces session age': 'SessionExpiryPolicy.isExpired(context, profile.id)' in MAIN,
    'expiry cancels only the same owner scheduled jobs': 'WeeklyBackupWorkScheduler.cancel(context, restoringOwnerId)' in MAIN and 'WeeklyBackupWorkScheduler.cancel(context, profile.id)' in MAIN,
    'expiry clears only session timestamp and does not delete inventory': 'SessionExpiryPolicy.clear(context, restoringOwnerId)' in MAIN and 'SessionExpiryPolicy.clear(context, profile.id)' in MAIN and 'AppDb' not in POLICY,
    'backup cadence is thirty days': 'PERIODIC_INTERVAL_DAYS = 30L' in WORK,
    'backup activates only after password-change gate and validated profile': MAIN.index('if (profile.mustChangePassword)') < MAIN.index('WeeklyBackupWorkScheduler.schedule(context, profile.id)'),
    'existing periodic schedule is updated rather than kept at seven days': 'ExistingPeriodicWorkPolicy.UPDATE' in WORK,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
