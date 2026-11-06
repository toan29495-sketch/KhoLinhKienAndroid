#!/usr/bin/env python3
"""Step167 non-destructive age classification for weekly backup journals."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
LIFE=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupLifecycle.kt').read_text()
REC=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
DATA=(ROOT/'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks=[]
def ck(name, ok): checks.append((name, bool(ok)))
ck('PREPARING stale threshold is explicit 24h', 'PREPARING_STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L' in LIFE)
ck('READY/UPLOADING stale threshold is explicit 30d', 'UPLOAD_STALE_AFTER_MILLIS = 30L * 24L * 60L * 60L * 1000L' in LIFE)
ck('clock rollback clamps age to zero', '(nowMillis - updatedAt).coerceAtLeast(0L)' in LIFE)
ck('fresh PREPARING is not marked stale', 'Classification.STALE_PREPARING_RECOVERABLE else Classification.FRESH' in LIFE)
ck('old PREPARING remains recoverable classification', 'STALE_PREPARING_RECOVERABLE' in LIFE)
ck('old READY/UPLOADING remains resumable classification', 'STALE_UPLOAD_RESUMABLE' in LIFE)
ck('VERIFIED is local-cleanup-only classification', '"VERIFIED" -> Classification.VERIFIED_LOCAL_CLEANUP_ONLY' in LIFE)
ck('FAILED and ABANDONED remain retained', '"FAILED", "ABANDONED" -> Classification.TERMINAL_RETAINED' in LIFE)
ck('unknown journal states fail closed', 'else -> throw IllegalArgumentException' in LIFE)
ck('recovery reports stale PREPARING count', 'stalePreparing: Int' in REC and 'stalePreparing++' in REC)
ck('recovery reports resumable stale upload count', 'staleUploadsResumable: Int' in REC and 'staleUploadsResumable++' in REC)
ck('recovery reports terminal retained count', 'terminalRetained: Int' in REC and 'terminalRetained++' in REC)
ck('age classification does not mutate journal state', 'UPDATE weekly_backup_journal' not in LIFE and 'markWeeklyBackupFailed' not in LIFE)
ck('age classification does not delete local or cloud files', '.delete(' not in LIFE and 'storage' not in LIFE.lower())
ck('recovery still preserves READY/UPLOADING archive bytes', 'if (valid) {\n                        resumable++' in REC)
ck('recovery keeps FAILED/ABANDONED audit rows', '"FAILED", "ABANDONED" -> Unit' in REC)
ck('Room schema remains v14', '@Database(' in DATA and 'version = 14' in DATA)
for name, ok in checks: print(f'{"PASS" if ok else "FAIL"}: {name}')
print(f'RESULT: {sum(ok for _,ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
