#!/usr/bin/env python3
"""Step173 contracts for reconciling a server-VERIFIED backup after process death."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
UP = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text()
REC = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks = {
    'registration recognizes only PENDING or VERIFIED server states': 'registration.status != "PENDING" && registration.status != "VERIFIED"' in UP,
    'VERIFIED server state skips a second completion RPC': 'if (registration.status == "VERIFIED")' in UP and UP.count('client.postgrest.rpc(\n                    "complete_inventory_backup"') == 1,
    'server VERIFIED state is accepted only after authenticated object download and digest verification': UP.index('if (!verifyStoredObject(') < UP.index('if (registration.status == "VERIFIED")') and 'downloadAuthenticatedTo(storagePath, target)' in UP and 'actualDigest.equals(expectedSha256' in UP,
    'reconciliation rechecks cancellation and owner before local commit': 'uploadCoroutineContext.ensureActive()\n            requireActiveOwner(prepared.ownerId)\n            require(dao.markWeeklyBackupVerified' in UP,
    'local VERIFIED transition remains owner and backup scoped': "WHERE ownerId=:ownerId AND backupId=:backupId AND state='UPLOADING'" in DAO,
    'local staging archive is deleted only after local VERIFIED commit': UP.index('dao.markWeeklyBackupVerified') < UP.index('prepared.archive.delete()'),
    'verification temp remains disposable in finally': 'finally {\n            // The verification copy is disposable' in UP and 'verifyFile.delete()' in UP,
    'READY and UPLOADING local journals remain resumable after restart': '"READY", "UPLOADING" -> {' in REC and 'resumable++ // Preserve exact path + bytes' in REC,
    'recovery does not contact Supabase or mutate remote objects': 'never contacts Supabase' in REC and 'storage.' not in REC,
    'backup identity and metadata must match before reconciliation': 'journal.archivePath != prepared.archive.canonicalPath' in UP and 'journal.sha256.equals(prepared.sha256' in UP,
    'owner fence surrounds the reconciliation read and remote verification': 'requireActiveOwner(prepared.ownerId)\n        WeeklyBackupRecovery.recoverOwner' in UP and 'requireActiveOwner(ownerId)' in UP,
    'VERIFIED reconciliation result remains explicit': 'val status = if (registration.status == "VERIFIED")' in UP and 'status = status' in UP,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
