#!/usr/bin/env python3
"""Step180: distinguish new weekly backup identity from resume identity."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt'
LIFE = ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupLifecycle.kt'
BACKUP = ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt'
DATA = ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt'
work, life, backup, data = (p.read_text() for p in (WORK, LIFE, BACKUP, DATA))
checks = {
    'worker obtains active journal before resolving identity': work.index('dao.activeWeeklyBackup(ownerId)') < work.index('WeeklyBackupLifecycle.backupIdForAttempt('),
    'worker delegates new-vs-resume decision to lifecycle policy': 'WeeklyBackupLifecycle.backupIdForAttempt(\n                active, UUID.randomUUID().toString()' in work,
    'no active journal selects proposed new UUID': 'if (active == null) return proposedBackupId' in life,
    'PREPARING resumes original backupId': 'active.state in setOf("PREPARING", "READY", "UPLOADING")' in life and 'return active.backupId' in life,
    'READY and UPLOADING retain existing backup identity': 'READY", "UPLOADING' in life and 'return active.backupId' in life,
    'terminal states cannot be silently replaced': 'Journal không ở trạng thái có thể tiếp tục' in life,
    'new identity must be canonical UUID': 'UUID.fromString(proposedBackupId).toString() == proposedBackupId' in life,
    'existing identity must be canonical UUID': 'UUID.fromString(active.backupId).toString() == active.backupId' in life,
    'preparation receives resolved identity unchanged': 'backupId = backupId' in work and 'backupId = backupId' in backup,
    'READY/UPLOADING archive is validated and returned without ZIP regeneration': 'if (journal.state == "READY" || journal.state == "UPLOADING")' in backup and 'return PreparedWeeklyCloudBackup(' in backup,
    'Room transaction still rejects another active journal': '@Transaction\n    suspend fun beginWeeklyBackup' in data and 'check(activeWeeklyBackup(journal.ownerId) == null)' in data,
    'no direct uploader call added outside worker': sum('.uploadAndVerify(prepared, backupId)' in p.read_text(errors='replace') for p in (ROOT / 'app/src/main').rglob('*.kt')) == 1,
    'no scheduler activation introduced': 'WeeklyBackupWorkScheduler.schedule(' not in work,
    'Room schema version unchanged': '@Database(entities = ' in data and 'version = 14' in data,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
