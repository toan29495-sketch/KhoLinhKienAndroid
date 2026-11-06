#!/usr/bin/env python3
"""Static checks for Step93 direct inventory owner isolation."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sql = (root / "supabase/migrations_pending_reconciliation/202611030005_lock_down_inventory_owner_rls.sql").read_text().lower()
admin_feed = (root / "supabase/migrations_pending_reconciliation/202611030003_null_safe_admin_feed_role.sql").read_text().lower()
restore = (root / "supabase/migrations_pending_reconciliation/202611020006_restore_commit_and_snapshot_read.sql").read_text().lower()
sync = (root / "supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql").read_text().lower()
checks = {
    "parts RLS explicitly enabled": "alter table public.parts enable row level security" in sql,
    "movements RLS explicitly enabled": "alter table public.movements enable row level security" in sql,
    "parts policy is restrictive": "create policy parts_owner_scope_authenticated\non public.parts\nas restrictive" in sql,
    "movements policy is restrictive": "create policy movements_owner_scope_authenticated\non public.movements\nas restrictive" in sql,
    "parts direct access bound to auth.uid": "using (owner_id = (select auth.uid()))" in sql,
    "parts writes preserve owner_id": "with check (owner_id = (select auth.uid()))" in sql,
    "movements direct access owner-scoped": sql.count("using (owner_id = (select auth.uid()))") == 2,
    "policies target authenticated only": sql.count("to authenticated") == 2,
    "global Admin feed remains SECURITY DEFINER": "security definer" in admin_feed and "v_role is null or lower(v_role) <> 'admin'" in admin_feed,
    "restore RPC retains explicit owner scope": "delete from public.movements where owner_id = v_owner" in restore and "delete from public.parts where owner_id = v_owner" in restore,
    "sync RPC retains explicit owner scope": "where id = p_entity_id and owner_id = v_owner" in sync,
    "migration is additive and transaction-wrapped": sql.startswith("-- step93") and "begin;" in sql and sql.rstrip().endswith("commit;"),
}
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"RESULT {sum(checks.values())}/{len(checks)}")
if not all(checks.values()):
    raise SystemExit(1)
