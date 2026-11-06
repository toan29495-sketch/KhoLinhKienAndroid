# Step86 – Harden backup photo preflight for device migration (2026-11-03)

## Findings
- `BackupManager.createZip()` opened the destination URI before downloading and validating cloud photos. A missing/unauthorized cloud object could fail the backup after truncating/creating the destination, and the output stream was not yet under the ZIP `use` block.
- Photo paths were trimmed when building the `photoEntries` map, but the JSONL writer looked up the original untrimmed `Part.photoPath`. A path containing surrounding whitespace could therefore be copied into the ZIP while its Part record silently omitted the `photoEntry` reference.

## Changes
- Delay opening the destination output stream until every referenced local photo has been found and every referenced cloud photo has been downloaded and size-checked.
- Keep the temporary photo directory in `try/finally` so preflight failures clean up downloaded temporary files.
- Normalize the lookup key when writing `photoEntry`, matching the normalized key used during preflight.
- Preserve existing owner-scoped cloud path validation, 20 MiB per-photo bound, streaming ZIP writes, and per-entry SHA-256 manifest checksums.
- No cloud objects or original local pending-upload files are deleted or moved.

## Verification
- `tools/verify_step86_backup_photo_preflight.py`: 7/7 PASS.
- Re-ran Step85 13/13; Step84 20/20; Step83 16/16; Step82 14/14; Step81 20/20; Step80 16/16; Step79 12/12; Step78 7/7; Step77 7/7; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; reconstructed Step49–72 24/24 + SQLite smoke test. All PASS.

## Boundaries / remaining risks
- This change does not make a Storage download or ZIP destination write atomic. Once ZIP writing begins, an I/O failure can still leave a partial destination file; the app has no portable transactional replacement guarantee for arbitrary SAF providers. The key improvement is that failures during photo preflight no longer open/truncate the destination.
- Cross-device restore still relies on the authenticated Storage upload/read-back verification path and subsequent restore snapshot protocol. Those external operations were not exercised against a live Supabase project.
- No Room runtime/instrumentation, Supabase Storage/PostgreSQL staging, real cross-device test, APK build, device test, SQL deployment, or GitHub push performed.
- This is an incremental change on the documented reconstructed Step49–72 source lineage, not byte-for-byte recovery of the lost original source.
