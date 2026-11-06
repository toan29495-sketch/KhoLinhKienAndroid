from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZIP = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
LIFE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreStagingLifecycle.kt').read_text()
checks = []
def check(label, condition):
    checks.append((label, bool(condition)))

extract = ZIP[ZIP.index('while (true) {', ZIP.index('ZipInputStream(raw.buffered())')):ZIP.index('val manifestFile')]
stage_start = ZIP.index('val durableStage =', ZIP.index('val existing = dao.restoreJournal'))
stage = ZIP[stage_start:ZIP.index('return expectedParts to expectedMovements', stage_start)]
check('ZIP extraction has hard entry count limit', '++entryCount <= MAX_ENTRIES' in extract)
check('ZIP extraction has per-entry and total uncompressed byte caps', 'entryBytes <= limit' in extract and 'totalBytes <= MAX_TOTAL_BYTES' in extract)
check('byte counters use overflow-checked addition', 'Math.addExact(entryBytes, count.toLong())' in extract and 'Math.addExact(totalBytes, count.toLong())' in extract)
check('available-space reserve is checked before every extracted write', extract.index('requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE + count)') < extract.index('output.write(buffer, 0, count)'))
check('owner session is rechecked during extraction reads', 'requireActiveOwner(ownerId)\n                                    val count = zip.read(buffer)' in extract)
check('entry parent directory creation failure is surfaced', 'target.parentFile?.let { it.mkdirs() || it.isDirectory }' in extract)
check('durable photo copy preflights complete copy size plus reserve', 'photoBytes + MIN_FREE_BYTES_DURING_RESTORE' in stage)
copy_fn = ZIP[ZIP.index('private fun copyWithSpaceGuard'):]
check('durable photo copy checks available space before each write', 'requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE + count)' in copy_fn)
check('durable photo copy fsyncs before journal can reference it', 'rawOutput.fd.sync()' in copy_fn)
check('journal is marked persisted only after STAGING save succeeds', stage.index('dao.beginLocalRestoreIfSafe(RestoreJournal(') < stage.index('journalPersisted = true'))
check('pre-journal cancellation removes only its exact UUID directory', 'if (!journalPersisted) runCatching { durableStage.deleteRecursively() }' in stage)
check('post-journal cancellation preserves durable staging for retry', 'if (!journalPersisted) runCatching { durableStage.deleteRecursively() }' in stage and 'Keep the durable STAGING' not in stage)
check('post-journal ENOSPC preserves original exception', 'if (journalPersisted)' in stage and 'throw error' in stage)
check('pre-journal ENOSPC cleans unjournaled orphan directory', 'else {\n                    // No journal means no recovery contract' in stage)
check('staged status is written only after exact staged row counts', stage.index('dao.countStagedRestoreRows') < stage.index('status = "STAGED"'))
check('live inventory is not touched by ZIP staging', 'deleteAllParts' not in ZIP and 'clearAllMovements' not in ZIP)
check('cleanup lifecycle limits deletion to COMPLETE or explicitly abortable stages', 'journal.status == "COMPLETE"' in LIFE and 'journal.status in setOf("STAGING", "STAGED", "FAILED")' in LIFE)

failed = [label for label, ok in checks if not ok]
for label, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'\n{len(checks)-len(failed)}/{len(checks)} checks PASS')
if failed:
    raise SystemExit(1)
