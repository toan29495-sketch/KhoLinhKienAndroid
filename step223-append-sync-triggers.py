#!/usr/bin/env python3
"""Step223: queue fresh outbox triggers behind an already-running sync worker."""
from pathlib import Path
import tempfile, zipfile, shutil

archive = Path("Step208-source.zip")
if not archive.is_file():
    raise SystemExit("Missing Step208-source.zip")

def patch(text: str, label: str) -> str:
    replacements = [
        ('manager.enqueueUniqueWork("inventory-sync-once-$ownerId", ExistingWorkPolicy.KEEP, request)',
         'manager.enqueueUniqueWork("inventory-sync-once-$ownerId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)'),
        ('manager.enqueueUniqueWork("inventory-sync-manual-$ownerId", ExistingWorkPolicy.KEEP, request)',
         'manager.enqueueUniqueWork("inventory-sync-manual-$ownerId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)'),
    ]
    for old, new in replacements:
        if old in text:
            text = text.replace(old, new, 1)
        elif new not in text:
            raise SystemExit(f"{label}: expected scheduler call not found: {old}")
    if text.count('ExistingWorkPolicy.APPEND_OR_REPLACE, request') != 2:
        raise SystemExit(f"{label}: expected exactly two append-or-replace outbox schedulers")
    return text

with tempfile.TemporaryDirectory() as td:
    root = Path(td)
    with zipfile.ZipFile(archive) as z:
        bad = z.testzip()
        if bad:
            raise SystemExit(f"Source ZIP is corrupt: {bad}")
        z.extractall(root)
    project = root / "KhoLinhKienAndroid"
    sync = project / "app/src/main/java/vn/kholinhkien/SyncWork.kt"
    if not sync.is_file():
        raise SystemExit("SyncWork.kt missing from canonical source archive")
    sync.write_text(patch(sync.read_text(encoding="utf-8"), "archive"), encoding="utf-8")

    rebuilt = root / "Step208-source.step223.zip"
    with zipfile.ZipFile(rebuilt, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(project.rglob("*")):
            if p.is_file():
                z.write(p, Path("KhoLinhKienAndroid") / p.relative_to(project))
    with zipfile.ZipFile(rebuilt) as z:
        if z.testzip():
            raise SystemExit("Rebuilt source archive failed ZIP integrity check")
        check = z.read("KhoLinhKienAndroid/app/src/main/java/vn/kholinhkien/SyncWork.kt").decode()
        if check.count("ExistingWorkPolicy.APPEND_OR_REPLACE, request") != 2:
            raise SystemExit("Step223 scheduler policy missing from rebuilt archive")
    shutil.copy2(rebuilt, archive)

live = Path("app/src/main/java/vn/kholinhkien/SyncWork.kt")
if not live.is_file():
    raise SystemExit("Live Gradle source SyncWork.kt missing")
live.write_text(patch(live.read_text(encoding="utf-8"), "live source"), encoding="utf-8")

progress = Path("PROGRESS.md")
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step223 — Preserve sync triggers while a worker is running (06/11/2026)"
    if heading not in log:
        log += """
\n
## Step223 — Preserve sync triggers while a worker is running (06/11/2026)
- Confirmed WorkManager dependency is androidx.work:work-runtime-ktx:2.10.0, which supports ExistingWorkPolicy.APPEND_OR_REPLACE.
- Root cause: automatic and manual outbox requests used unique-work KEEP. If a worker was already ENQUEUED/RUNNING, a later mutation's request could be discarded. The sender reads its pending queue for that execution, so newly enqueued operations might wait for the 15-minute periodic fallback.
- Change both outbox scheduling paths (automatic and manual) to APPEND_OR_REPLACE. New triggers are chained after the active work rather than dropped; an active worker is not cancelled. Pull workers and periodic schedules are unchanged.
- Owner UUID validation, connected-network constraint, queue ordering, operation IDs, restore gate, and sender idempotency are unchanged. No local or server transaction is deleted or rewritten.
- Source/archive verification included in Step223 patch. Build and device background verification pending.
"""
        progress.write_text(log, encoding="utf-8")
print("Step223 source patch PASS: both outbox triggers append safely; source archive integrity PASS.")
