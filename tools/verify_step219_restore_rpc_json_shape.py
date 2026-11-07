#!/usr/bin/env python3
"""Source-contract checks for Step219 restore RPC JSON shape normalization."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/vn/kholinhkien"
helper = (SRC / "RestoreRpcJson.kt").read_text(encoding="utf-8")
committer = (SRC / "RestoreCloudCommitter.kt").read_text(encoding="utf-8")
reader = (SRC / "RestoreCloudSnapshotReader.kt").read_text(encoding="utf-8")
catalog = (SRC / "WeeklyBackupCloudCatalog.kt").read_text(encoding="utf-8")

checks = {
    "shared RPC parser accepts JsonElement directly": "is JsonElement -> raw" in helper,
    "shared RPC parser unwraps bounded nested JSON primitive layers": "repeat(4)" in helper and "parser.parseToJsonElement(text)" in helper and "looksLikeQuotedJson" in helper,
    "object parser rejects non-object with diagnostic": "as? JsonObject" in helper and "cần JSON object" in helper,
    "array parser rejects non-array with diagnostic": "as? JsonArray" in helper and "cần JSON array" in helper,
    "commit response uses normalized parser": 'RestoreRpcJson.objectValue(raw, "commit_inventory_restore")' in committer,
    "snapshot response uses normalized parser": 'RestoreRpcJson.objectValue(raw, "read_inventory_restore_snapshot_chunk")' in reader,
    "snapshot verification uses normalized parser": 'RestoreRpcJson.objectValue(verificationRaw, "verify_inventory_restore_snapshot_chunk")' in reader,
    "snapshot payload checks array type without unsafe jsonArray cast": "as? kotlinx.serialization.json.JsonArray" in reader and ".jsonArray" not in reader,
    "cloud backup catalog uses normalized array parser": 'RestoreRpcJson.arrayValue(raw, "list_my_inventory_backups")' in catalog,
    "restore commit remains idempotent on unknown result": 'status = "COMMIT_UNKNOWN"' in committer and 'restoreId so the server returns' in committer,
    "live inventory is not written by RPC parser": "dao.restoreJournal" not in helper,
}
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " " + name)
print(f"RESULT {sum(checks.values())}/{len(checks)}")
raise SystemExit(0 if all(checks.values()) else 1)
