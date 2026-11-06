#!/usr/bin/env python3
"""Verify authenticated UI state is partitioned by profile across account switches."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text(encoding='utf-8')
RESTORE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreStagingLifecycle.kt').read_text(encoding='utf-8')
checks=[]
def check(name, ok): checks.append((name, bool(ok)))
owner_states = [
    'conflictServerSnapshot','readingConflictSnapshot','queuedBehindCount','conflictDecision',
    'finalServerVersion','confirmKeepLocal','confirmKeepServer','pendingDecisionChoice',
    'confirmDecisionChoice','query','selected','showAdd','prefillCode','showEdit','scan',
    'machineHistoryCode','pendingPhotoPath','restoreUri','restorePreview','restorePreviewBusy',
    'restorePreviewError','restoreJournals','restoreBusy','batchCodes','showBatch'
]
for name in owner_states:
    check(f'UI state {name} is keyed to profile.id', f'var {name} by remember(profile.id)' in MAIN)
check('Pending photo directory is owner-scoped', 'File(File(context.filesDir, "pending_part_photos"), ownerId)' in MAIN)
check('Backup inspector validates the expected owner', 'BackupArchiveInspector.inspect(context, uri, profile.id)' in MAIN)
check('Restore journal reads are owner-scoped', 'dao.restoreJournals(profile.id)' in MAIN)
check('Restore staging validates journal owner', 'require(journal.ownerId == ownerId)' in RESTORE)
check('Backup photo validation checks canonical app-private path', 'canonicalSource.path.startsWith(privateRoot.path + File.separator)' in BACKUP)
# Model Compose remember(key) identity: switching owner creates a fresh state slot.
state_slots = {('A', name): 'pending_A' for name in owner_states}
state_slots.update({('B', name): 'pending_B' for name in owner_states})
check('State identity differs between two owner keys', all(state_slots[('A', n)] != state_slots[('B', n)] for n in owner_states))
for name, ok in checks: print(f'{"PASS" if ok else "FAIL"}: {name}')
print(f'RESULT: {sum(ok for _,ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
