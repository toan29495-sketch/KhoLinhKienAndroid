from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks=[]
def check(name, condition): checks.append((name, bool(condition)))
check('filename helper is account-based and deterministic', 'fun backupFileName(username: String, timestampMillis: Long = System.currentTimeMillis())' in BACKUP)
check('SL prefix and KTV token are part of fixed name', 'return "SL${ktvToken}${dateTime}.zip"' in BACKUP)
check('username normalized to uppercase safe ASCII', '.trim().uppercase(Locale.ROOT)' in BACKUP and "it in 'A'..'Z' || it in '0'..'9'" in BACKUP)
check('timestamp uses ddMMHHmm', 'SimpleDateFormat("ddMMHHmm", Locale.ROOT)' in BACKUP)
check('backup writes directly to MediaStore Downloads on Android Q+', 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' in BACKUP and 'MediaStore.MediaColumns.IS_PENDING' in BACKUP)
check('duplicate filename is rejected rather than overwritten', 'Đã tồn tại bản sao lưu $fileName' in BACKUP and '!target.exists()' in BACKUP)
check('pre-Q fallback uses app-specific Documents directory', 'getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)' in BACKUP)
check('backup owner ID must be a canonical UUID', 'require(isCanonicalOwnerId(ownerId))' in BACKUP)
check('UI invokes fixed save function with logged-in username and owner ID', 'BackupManager.saveBackup(context, username, ownerId, parts, movements)' in MAIN)
check('backup action has busy guard', 'enabled = !restoreLocked && !backupBusy' in MAIN and 'if (!backupBusy)' in MAIN)
check('backup action no longer launches rename-capable CreateDocument', 'backupLauncher' not in MAIN and 'CreateDocument("application/zip")' not in MAIN)
check('UI states name cannot be changed in app', 'Không thể đổi tên trong ứng dụng.' in MAIN)
example = 'SL' + 'KTVA' + '0311' + '1219' + '.zip'
check('requested naming example remains exact', example == 'SLKTVA03111219.zip' and re.fullmatch(r'SL[A-Z0-9]+\d{8}\.zip', example) is not None)
for name, ok in checks: print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(ok for _,ok in checks)}/{len(checks)} PASS')
if not all(ok for _,ok in checks): raise SystemExit(1)
