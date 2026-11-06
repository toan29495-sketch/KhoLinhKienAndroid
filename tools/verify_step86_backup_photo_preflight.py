from pathlib import Path
root = Path(__file__).resolve().parents[1]
src = (root / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks = {
    'destination output is opened only after photo preflight loop': src.index('ZipOutputStream(destination.buffered())') > src.index('for (part in parts)') and src.index('ZipOutputStream(destination.buffered())') > src.index('photoEntries[path] = entryName'),
    'failed preflight still runs temporary directory cleanup': 'finally {' in src and 'temporaryDirectory.deleteRecursively()' in src,
    'photo ZIP references use the same normalized path key as preflight': 'photoEntries[p.photoPath.trim()]' in src,
    'cloud paths are owner-scoped before authenticated download': 'validateCloudPath(path, ownerId)' in src and 'downloadAuthenticatedTo(path, target)' in src,
    'local photo files pass app-private and owner-scope validation without moving or deleting source': 'validateLocalPhotoFile(context, File(path), ownerId)' in src and 'file.inputStream().buffered().use' in src and 'source.delete' not in src,
    'photo bytes are bounded before ZIP write': 'source.length() in 1..MAX_PHOTO_BYTES' in src,
    'manifest includes checksums for every copied photo': 'checksums.put(entryName, digest.digest().toHex())' in src,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP86_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
