#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
backup = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
inspector = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupArchiveInspector.kt').read_text()
main = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
restore = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

check('manifest persists authenticated KTV username', 'backupUsername", username.trim().uppercase(Locale.ROOT)' in backup)
check('manifest persists backup timestamp', '.put("createdAt", timestampMillis)' in backup)
check('manifest persists immutable generated filename', '.put("fileName", backupFileName(username, timestampMillis))' in backup)
check('backup owner id remains in manifest', '.put("ownerId", ownerId)' in backup)
check('manifest schema version remains backward compatible v2', '.put("version", 2)' in backup)
check('inspector caps manifest size', 'MAX_MANIFEST_BYTES = 1024L * 1024L' in inspector and 'total <= MAX_MANIFEST_BYTES' in inspector)
check('inspector reads bounded manifest without extracting archive payload', 'found ?: error("ZIP không có manifest.json")' in inspector and 'MAX_MANIFEST_BYTES' in inspector)
check('inspector validates supported version', 'require(version == 2)' in inspector)
check('inspector requires owner identity', 'ownerId.isNotBlank()' in inspector)
check('inspector rejects cross-account restore before confirmation', 'ownerId == expectedOwnerId' in inspector and 'không cho khôi phục chéo tài khoản' in inspector)
check('inspector validates part and movement counts', 'parts in 0..500_000 && movements in 0..1_000_000' in inspector)
check('legacy backups have username fallback', 'KTV (bản sao lưu cũ)' in inspector)
check('legacy backups have timestamp fallback', 'createdAt = manifest.optLong("createdAt", 0L)' in inspector)
check('picker starts metadata inspection on IO dispatcher', 'BackupArchiveInspector.inspect(context, uri, profile.id)' in main and 'withContext(Dispatchers.IO)' in main)
check('confirmation displays KTV identity and timestamp', 'Tài khoản tạo: ${preview.username}' in main and 'preview.createdAt' in main)
check('confirmation displays inventory and movement counts', 'preview.partCount} linh kiện · ${preview.movementCount} giao dịch' in main)
check('confirmation disabled until valid same-owner preview', '!restorePreviewBusy && restorePreview != null && restorePreviewError.isBlank()' in main)
check('full ZIP restore retains checksum validation', 'checksumNames == expectedNames' in restore and 'sha256(File(stage, name))' in restore)
check('full ZIP restore independently rechecks owner', 'backupOwner == ownerId' in restore)
check('manifest preview remains metadata-only while UI promises full restore validation', 'found ?: error("ZIP không có manifest.json")' in inspector and 'Ứng dụng vẫn sẽ kiểm tra toàn bộ ZIP và SHA-256 trước khi cập nhật kho.' in main and 'checksumNames == expectedNames' in restore)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
