#!/usr/bin/env python3
"""Static contract checks for Step159; does not contact or mutate Supabase."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
review = (ROOT / "STEP159_REVIEW.md").read_text(encoding="utf-8")
source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
adapter = (ROOT / "docs/SUPABASE_STORAGE_301_RESTORE_ADAPTER.md").read_text(encoding="utf-8")

checks = {
    "dedicated private bucket specified": "inventory-backups" in review and "Private bucket" in review,
    "owner-prefixed object path specified": "<auth.uid()>/<backup_uuid>.zip" in review,
    "immutable no-upsert upload specified": "upsert=false" in review,
    "restrictive owner-scope guard classification documented": "part_images_owner_scope_guard` is **RESTRICTIVE**" in review,
    "restrictive delete guard classification documented": "part_images_no_authenticated_delete` is **RESTRICTIVE**" in review,
    "permissive policies classified separately": "actual **PERMISSIVE** policies" in review and "default-deny" in review,
    "admin status-only metadata contract specified": "status-only RPC" in review and "download capability for other owners" in review,
    "owner-derived auth.uid contract specified": "derive owner from `auth.uid()`" in review,
    "server-side hash limitation disclosed": "cannot independently prove the file's SHA-256" in review,
    "safe retention order specified": "new upload is verified" in review and "Never delete the last verified backup" in review,
    "no service-role client secret specified": "No service-role key in Android" in review,
    "pinned Kotlin BOM 3.0.1 remains": 'bom:3.0.1' in source,
    "existing Kotlin adapter evidence retained": "downloadAuthenticated" in adapter and "upsert=true" in adapter,
    "no live changes claimed": "No SQL DDL, bucket creation, policy changes" in review,
    "no false policy bypass claim remains": "not a live access-control bypass" in review,
}

for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
raise SystemExit(0 if all(checks.values()) else 1)
