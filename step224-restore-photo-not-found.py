#!/usr/bin/env python3
"""Step224: treat only a genuine Storage 404 as a missing restore photo."""
from pathlib import Path
import shutil
import tempfile
import zipfile

ARCHIVE = Path("Step208-source.zip")
REL = Path("app/src/main/java/vn/kholinhkien/SupabaseRestorePhotoStorage.kt")

def patch(source: str, label: str) -> str:
    import_anchor = "import io.github.jan.supabase.storage.storage\n"
    needed_imports = (
        "import io.ktor.client.plugins.ResponseException\n"
        "import io.ktor.http.HttpStatusCode\n"
    )
    if "import io.ktor.client.plugins.ResponseException" not in source:
        if import_anchor not in source:
            raise SystemExit(f"{label}: cannot find Supabase Storage import anchor")
        source = source.replace(import_anchor, import_anchor + needed_imports, 1)

    old = '''        val downloaded = client.storage.from(BUCKET).downloadAuthenticated(remotePath)
        return downloaded.size.toLong() == expectedBytes &&
            sha256(downloaded).equals(expectedSha256, ignoreCase = true)'''
    new = '''        val downloaded = try {
            client.storage.from(BUCKET).downloadAuthenticated(remotePath)
        } catch (error: ResponseException) {
            // A missing object is an expected state before the first restore upload.
            // Do not swallow 401/403, server errors, or transport failures.
            if (error.response.status == HttpStatusCode.NotFound) return false
            throw error
        }
        return downloaded.size.toLong() == expectedBytes &&
            sha256(downloaded).equals(expectedSha256, ignoreCase = true)'''
    if old in source:
        source = source.replace(old, new, 1)
    elif new not in source:
        raise SystemExit(f"{label}: expected authenticated download verifier not found")

    if source.count("error.response.status == HttpStatusCode.NotFound") != 1:
        raise SystemExit(f"{label}: expected one explicit 404-only branch")
    if "throw error" not in source:
        raise SystemExit(f"{label}: non-404 errors must propagate")
    return source

if not ARCHIVE.is_file():
    raise SystemExit("Missing canonical Step208-source.zip")

with tempfile.TemporaryDirectory() as td:
    root = Path(td)
    with zipfile.ZipFile(ARCHIVE) as z:
        bad = z.testzip()
        if bad:
            raise SystemExit(f"Source ZIP is corrupt: {bad}")
        z.extractall(root)
    project = root / "KhoLinhKienAndroid"
    target = project / REL
    if not target.is_file():
        raise SystemExit("SupabaseRestorePhotoStorage.kt missing from source archive")
    target.write_text(patch(target.read_text(encoding="utf-8"), "archive"), encoding="utf-8")
    rebuilt = root / "Step208-source.step224.zip"
    with zipfile.ZipFile(rebuilt, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(project.rglob("*")):
            if path.is_file():
                z.write(path, Path("KhoLinhKienAndroid") / path.relative_to(project))
    with zipfile.ZipFile(rebuilt) as z:
        if z.testzip():
            raise SystemExit("Rebuilt Step224 archive failed integrity check")
        packed = z.read("KhoLinhKienAndroid/" + REL.as_posix()).decode()
        if "error.response.status == HttpStatusCode.NotFound" not in packed:
            raise SystemExit("Step224 404 handling missing from rebuilt archive")
    shutil.copy2(rebuilt, ARCHIVE)

live = Path("app/src/main/java/vn/kholinhkien/SupabaseRestorePhotoStorage.kt")
if not live.is_file():
    raise SystemExit("Live Gradle source SupabaseRestorePhotoStorage.kt missing")
live.write_text(patch(live.read_text(encoding="utf-8"), "live source"), encoding="utf-8")

progress = Path("PROGRESS.md")
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step224 — Handle missing restore photos before upload (06/11/2026)"
    if heading not in log:
        log += """
\n
## Step224 — Handle missing restore photos before upload (06/11/2026)
- Root cause: restore photo verification downloads the expected object before upload. A Storage 404 was propagated as a fatal error instead of being interpreted as "not uploaded yet", preventing the upload stage.
- Change: only HTTP 404 from authenticated Storage download returns false, allowing the existing coordinator to upload the ZIP photo and then independently verify it.
- Authentication/authorization errors, server errors, and transport failures continue to propagate. SHA-256 and byte-count verification remain mandatory after upload.
- No restore journal, ZIP staging file, checkpoint, inventory row, sync operation, or cloud object is deleted or rewritten by this patch.
- Build and device restore verification pending.
"""
        progress.write_text(log, encoding="utf-8")
print("Step224 patch PASS: only Storage HTTP 404 is treated as missing; source ZIP integrity PASS.")
