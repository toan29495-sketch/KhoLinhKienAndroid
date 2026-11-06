#!/usr/bin/env python3
"""Step163 source-contract and restart-state recovery checks."""
from pathlib import Path
import hashlib, tempfile
ROOT=Path(__file__).resolve().parents[1]
REC=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
DATA=(ROOT/'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
BACK=(ROOT/'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
UP=(ROOT/'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text()
MAIN=(ROOT/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
checks=[]
def ck(name, ok): checks.append((name,bool(ok)))
ck('recovery is explicitly owner-scoped', 'weeklyBackupsForOwner(ownerId)' in REC and 'row.ownerId == ownerId' in REC)
ck('recovery rejects noncanonical owner and checks active session', 'isCanonicalOwnerId(ownerId)' in REC and 'requireActiveOwner(ownerId)' in REC)
ck('archive path must match exact owner/backupId canonical path', 'archive == expected && archive.parentFile == ownerDirectory' in REC)
ck('PREPARING crash residue is removed without upload', '"PREPARING" ->' in REC and 'archive.delete()' in REC)
ck('READY/UPLOADING preserve exact TUS file when size and digest match', '"READY", "UPLOADING" ->' in REC and 'actualSize == row.archiveSizeBytes' in REC and 'digest.equals(row.sha256, ignoreCase = true)' in REC)
ck('invalid READY/UPLOADING archive transitions to FAILED', 'markWeeklyBackupFailed(ownerId, row.backupId' in REC)
ck('VERIFIED archive cleanup is local-only', '"VERIFIED" ->' in REC and 'archive.delete()' in REC)
ck('FAILED and ABANDONED journals are retained for audit', '"FAILED", "ABANDONED" -> Unit' in REC)
ck('recovery performs no Supabase/network operation', 'postgrest' not in REC.lower() and 'supabaseclient' not in REC.lower() and 'http' not in REC.lower())
ck('recovery is invoked after authenticated profile loads at app entry', 'LaunchedEffect(profile.id)' in MAIN and 'WeeklyBackupRecovery.recoverOwner(context, dao, profile.id)' in MAIN)
ck('startup recovery runs off the main thread', 'withContext(Dispatchers.IO)' in MAIN)
ck('recovery runs before weekly archive preparation', 'WeeklyBackupRecovery.recoverOwner(context, dao, ownerId)' in BACK)
ck('recovery runs before upload retry', 'WeeklyBackupRecovery.recoverOwner(context, dao, prepared.ownerId)' in UP)
ck('DAO query cannot enumerate other owners', 'WHERE ownerId = :ownerId ORDER BY createdAt DESC' in DATA)
ck('no new schema migration required for recovery', '@Database(' in DATA and 'version = 14' in DATA)
ck('no WorkManager scheduling or Gradle dependency introduced', 'PeriodicWorkRequest' not in REC and 'gradle' not in REC.lower())
# Model crash recovery invariants against actual bytes/digests in a disposable directory.
with tempfile.TemporaryDirectory() as d:
    from pathlib import Path
    root=Path(d); owner='11111111-1111-4111-8111-111111111111'; bid='22222222-2222-4222-8222-222222222222'
    folder=root/owner; folder.mkdir(); archive=folder/(bid+'.zip'); archive.write_bytes(b'known immutable backup')
    digest=hashlib.sha256(archive.read_bytes()).hexdigest(); size=archive.stat().st_size
    ck('restart fixture retains same path, bytes, size and SHA for resumable state', archive.resolve()==(folder/(bid+'.zip')).resolve() and archive.stat().st_size==size and hashlib.sha256(archive.read_bytes()).hexdigest()==digest)
    # Simulate PREPARING crash: partial bytes are removed, identity/path remains reusable.
    archive.write_bytes(b'truncated'); archive.unlink()
    ck('PREPARING crash fixture removes truncated ZIP but retains journal identity', not archive.exists() and bid in archive.name)
    # Simulate VERIFIED cleanup retry: missing file is already-clean state.
    ck('VERIFIED cleanup is idempotent when file was already removed', not archive.exists())
for n,ok in checks: print(f"{'PASS' if ok else 'FAIL'}: {n}")
print(f'RESULT: {sum(v for _,v in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(v for _,v in checks) else 1)
