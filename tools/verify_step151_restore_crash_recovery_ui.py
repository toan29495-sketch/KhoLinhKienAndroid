#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/vn/kholinhkien'
MAIN = (BASE / 'MainActivity.kt').read_text()
ZIP = (BASE / 'ZipRestore.kt').read_text()
COORD = (BASE / 'RestoreCloudCoordinator.kt').read_text()
checks=[]
def check(name, condition): checks.append((name, bool(condition)))

# Interrupted local ZIP extraction is a special recovery path: the original stream
# cannot be assumed to survive Android process death, so the user must reselect it.
check('STAGING and FAILED offer reselect-ZIP action',
      'journal.status in setOf("STAGING", "FAILED")' in MAIN and
      'restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))' in MAIN)
check('UI clearly explains that exact original ZIP must be selected again',
      'Chọn lại ZIP để dựng lại staging' in MAIN and 'đúng tệp ZIP cũ' in MAIN)
check('reselect path is restricted to local ZIP journals, not remote feed',
      'journal.sourceType != "REMOTE_FEED" && journal.status in setOf("STAGING", "FAILED")' in MAIN)
check('confirmation permits only local STAGING/FAILED journal retry',
      'activeRestore == null ||' in MAIN and
      'activeRestore.sourceType != "REMOTE_FEED" && activeRestore.status in setOf("STAGING", "FAILED")' in MAIN)
check('different active restore archive remains rejected by deterministic restoreId fence',
      'active == null || active.restoreId == restoreId' in ZIP)
check('same archive restoreId remains deterministic from owner and manifest digest',
      '"$ownerId:$manifestDigest".toByteArray(Charsets.UTF_8)' in ZIP and
      'UUID.nameUUIDFromBytes' in ZIP)
check('re-import of incomplete staging removes only exact owner restore journal rows',
      'dao.deleteAbortableRestoreJournal(ownerId, restoreId)' in ZIP and
      'dao.clearRestoreStagedRows(ownerId, restoreId)' in ZIP)
check('process cancellation preserves STAGING journal rather than marking failed',
      'catch (cancelled: CancellationException)' in ZIP and
      'Keep the durable STAGING journal' in ZIP)
check('staging is marked STAGED only after all part and movement row counts match',
      'dao.countStagedRestoreRows(ownerId, restoreId, "PART") == expectedParts' in ZIP and
      'dao.countStagedRestoreRows(ownerId, restoreId, "MOVEMENT") == expectedMovements' in ZIP and
      'status = "STAGED"' in ZIP)
check('resume from later phases remains exact owner and restoreId bound',
      'dao.restoreJournal(ownerId, restoreId)' in COORD and
      'require(journal.ownerId == ownerId)' in COORD)
check('live Room inventory apply remains gated on SNAPSHOT_DOWNLOADED',
      'if (journal.status == "SNAPSHOT_DOWNLOADED")' in COORD and
      'RestoreCloudSnapshotApplier(context.applicationContext, dao).apply(ownerId, restoreId)' in COORD)
check('no destructive server reset or cloud object cleanup introduced',
      'delete' not in MAIN[MAIN.index('onResumeRestore ='):MAIN.index('onMessage = { message = it }')] and
      'deleteObject' not in ZIP)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _,ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
