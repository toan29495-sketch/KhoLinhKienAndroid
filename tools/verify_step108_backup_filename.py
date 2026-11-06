from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks=[]
def check(name, condition): checks.append((name, bool(condition)))
check('fixed filename helper uses account and timestamp', 'fun backupFileName(username: String, timestampMillis: Long = System.currentTimeMillis())' in BACKUP)
check('filename uses SL prefix and KTV token', 'return "SL${ktvToken}${dateTime}.zip"' in BACKUP)
check('username normalized uppercase', '.trim().uppercase(Locale.ROOT)' in BACKUP)
check('filename token excludes unsafe path characters', "it in 'A'..'Z' || it in '0'..'9'" in BACKUP)
check('empty username has safe KTV fallback', '.ifBlank { "KTV" }' in BACKUP)
check('date-time format is day month hour minute', 'SimpleDateFormat("ddMMHHmm", Locale.ROOT)' in BACKUP)
check('tools screen passes authenticated username and owner ID', 'BackupManager.saveBackup(context, username, ownerId, parts, movements)' in MAIN)
check('backup name is generated internally', 'BackupManager.backupFileName(username)' not in MAIN)
check('document picker is not used for ZIP backup', 'CreateDocument("application/zip")' not in MAIN)
check('fixed destination is Downloads on modern Android', 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' in BACKUP)
check('requested example matches format', 'SLKTVA03111219.zip' in MAIN)
for name, ok in checks: print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(ok for _,ok in checks)}/{len(checks)} PASS')
if not all(ok for _,ok in checks): raise SystemExit(1)
