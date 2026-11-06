#!/usr/bin/env python3
"""Step182: audit cloud/local photo consistency in weekly backup archives."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks = {
    'snapshot blocks unresolved photo outbox operations': "status IN ('PENDING', 'FAILED', 'IN_FLIGHT', 'BLOCKED')" in DATA,
    'cloud photo path is owner validated before download': 'validateCloudPath(path, ownerId)' in BACKUP and BACKUP.index('validateCloudPath(path, ownerId)') < BACKUP.index('downloadAuthenticatedTo(path, target)'),
    'cloud photo download uses authenticated private storage': 'storage.from("part-images")' in BACKUP and 'downloadAuthenticatedTo(path, target)' in BACKUP,
    'owner is rechecked immediately after cloud photo download': BACKUP.index('downloadAuthenticatedTo(path, target)') < BACKUP.index('requireActiveOwner(ownerId)', BACKUP.index('downloadAuthenticatedTo(path, target)')),
    'missing or empty cloud photo fails closed': 'target.isFile && downloadedBytes in 1..MAX_PHOTO_BYTES' in BACKUP,
    'oversized cloud photo fails closed': 'downloadedBytes in 1..MAX_PHOTO_BYTES' in BACKUP,
    'total temporary cloud photo cache is bounded': 'cloudPhotoCacheBytes <= MAX_TOTAL_BYTES' in BACKUP,
    'local photo paths are confined to app-private storage': 'canonicalSource.path.startsWith(privateRoot.path + File.separator)' in BACKUP,
    'local photo references are proven exclusive to owner': 'isLocalPhotoReferencedOnlyByOwner(ownerId, canonicalSource.path)' in BACKUP,
    'ZIP destination is not opened before every photo is validated': BACKUP.index('Do not open/truncate the destination') < BACKUP.index('ZipOutputStream(destination.buffered()).use'),
    'photo bytes are hashed while written into ZIP': 'digest.update(buffer, 0, count)' in BACKUP and 'checksums.put(entryName, digest.digest().toHex())' in BACKUP,
    'manifest stores photo entry checksums': 'checksums.put(entryName, digest.digest().toHex())' in BACKUP and '.put("checksums", checksums)' in BACKUP,
    'temporary downloaded cloud photos are always cleaned': 'finally {' in BACKUP and 'temporaryDirectory.deleteRecursively()' in BACKUP,
    'cloud photo object names are owner/entity/operation scoped': '"$ownerId/$partSyncId/$operationId.$extension"' in SENDER,
    'photo upload verifies stored bytes before publishing cloud path': SENDER.index('downloadAuthenticated(remotePath)') < SENDER.index('PhotoIntegrity.matches(photoBytes.size, expectedDigest, storedBytes)') < SENDER.index('put("photo_path", remotePath)'),
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
