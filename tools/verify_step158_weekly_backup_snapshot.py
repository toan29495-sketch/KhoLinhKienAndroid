#!/usr/bin/env python3
"""Source contract and SQLite snapshot-isolation checks for Step158.

This does not compile Android/Room or execute BackupManager on a device.
"""
from pathlib import Path
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[1]
data = (ROOT / "app/src/main/java/vn/kholinhkien/Data.kt").read_text()
backup = (ROOT / "app/src/main/java/vn/kholinhkien/BackupManager.kt").read_text()
inspector = (ROOT / "app/src/main/java/vn/kholinhkien/BackupArchiveInspector.kt").read_text()
checks = []

def check(name, condition):
    checks.append((name, bool(condition)))

# DAO capture contract.
method_start = data.index("suspend fun captureAutomaticBackupSnapshot")
method_end = data.index("\n    @Query(", method_start)
method = data[method_start:method_end]
check("snapshot DAO is a Room transaction", "@Transaction" in data[max(0, method_start-40):method_start])
check("snapshot requires owner id", 'require(ownerId.isNotBlank())' in method)
check("snapshot blocks active restore", "activeRestore(ownerId) != null" in method)
check("snapshot blocks unsent outbox", "countUnsentOutbox(ownerId) != 0" in method)
check("snapshot reads owner-scoped parts", "backupPartsForOwner(ownerId)" in method and 'WHERE ownerId = :ownerId' in data)
check("snapshot reads owner-scoped movements", "backupMovementsForOwner(ownerId)" in method)
check("snapshot captures sync cursor", "getSyncCursor(ownerId)" in method)
check("snapshot returns immutable owner envelope", "InventoryBackupSnapshot(ownerId, cursor, parts, movements)" in method)

# Archive preparation contract.
prepare_start = backup.index("suspend fun prepareWeeklyCloudBackup")
prepare_end = backup.index("\n    /** Fixed backup name", prepare_start)
prepare = backup[prepare_start:prepare_end]
check("automatic archive uses DAO snapshot", "captureAutomaticBackupSnapshot(ownerId)" in prepare)
check("automatic archive is durable private filesDir output", "context.filesDir" in prepare and "weekly-backup-staging" in prepare and '"$backupId.zip"' in prepare)
check("automatic archive is not published to Downloads", "MediaStore" not in prepare and "Downloads" not in prepare)
check("automatic archive checks active owner", prepare.count("requireActiveOwner(ownerId)") >= 4)
check("automatic archive verifies ZIP payload and central directory", "BackupArchiveInspector.verify" in prepare and "ZipCentralDirectoryVerifier.verify" in prepare)
check("automatic archive computes SHA-256", "MessageDigest.getInstance(\"SHA-256\")" in prepare)
check("automatic archive deletes partial on failure", "catch (error: Throwable)" in prepare and "archive.delete()" in prepare)
check("weekly manifest records kind and cursor", 'backupKind = "WEEKLY_AUTO"' in prepare and "snapshotCursor = snapshot.syncCursor" in prepare)
check("inspector rejects weekly backup without cursor", 'backupKind == "WEEKLY_AUTO"' in inspector and 'manifest.has("snapshotCursor")' in inspector)

# SQLite WAL model: one read transaction must see a coherent pre-write snapshot.
with tempfile.TemporaryDirectory() as tmp:
    db = Path(tmp) / "snapshot.db"
    c1 = sqlite3.connect(db, isolation_level=None, timeout=2)
    c2 = sqlite3.connect(db, isolation_level=None, timeout=2)
    c1.execute("PRAGMA journal_mode=WAL")
    for c in (c1, c2):
        c.execute("CREATE TABLE IF NOT EXISTS parts(owner TEXT, code TEXT)")
        c.execute("CREATE TABLE IF NOT EXISTS movements(owner TEXT, event TEXT)")
        c.execute("CREATE TABLE IF NOT EXISTS outbox(owner TEXT, status TEXT)")
        c.execute("CREATE TABLE IF NOT EXISTS restores(owner TEXT, status TEXT)")
        c.execute("CREATE TABLE IF NOT EXISTS cursors(owner TEXT, cursor INTEGER)")
    c1.execute("INSERT INTO parts VALUES('A','P1')")
    c1.execute("INSERT INTO movements VALUES('A','M1')")
    c1.execute("INSERT INTO cursors VALUES('A',10)")
    c1.execute("INSERT INTO parts VALUES('B','SECRET')")
    c1.execute("COMMIT") if c1.in_transaction else None
    c1.execute("BEGIN")
    p_before = c1.execute("SELECT code FROM parts WHERE owner='A' ORDER BY code").fetchall()
    c2.execute("BEGIN IMMEDIATE")
    c2.execute("INSERT INTO parts VALUES('A','P2')")
    c2.execute("INSERT INTO movements VALUES('A','M2')")
    c2.execute("COMMIT")
    m_during = c1.execute("SELECT event FROM movements WHERE owner='A' ORDER BY event").fetchall()
    cursor_during = c1.execute("SELECT cursor FROM cursors WHERE owner='A'").fetchone()[0]
    c1.execute("COMMIT")
    p_after = c1.execute("SELECT code FROM parts WHERE owner='A' ORDER BY code").fetchall()
    check("SQLite read transaction sees coherent old parts+movements", p_before == [("P1",)] and m_during == [("M1",)])
    check("SQLite read transaction retains matching cursor", cursor_during == 10)
    check("next snapshot sees both committed changes", p_after == [("P1",), ("P2",)])
    check("snapshot owner filter excludes other KTV rows", c1.execute("SELECT code FROM parts WHERE owner='A'").fetchall() == [("P1",), ("P2",)])
    c1.close(); c2.close()

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"RESULT: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
