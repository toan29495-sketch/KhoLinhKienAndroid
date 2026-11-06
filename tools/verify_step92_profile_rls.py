#!/usr/bin/env python3
"""Static regression checks for Step92 profile RLS hardening."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sql = (root / "supabase/migrations_pending_reconciliation/202611030004_lock_down_profile_access.sql").read_text().lower()
checks = {
    "profiles RLS enabled": "alter table public.profiles enable row level security" in sql,
    "admin helper is SECURITY DEFINER": "create or replace function public.is_active_admin_profile()" in sql and "security definer" in sql,
    "admin helper requires active profile": "p.is_active = true" in sql,
    "admin helper requires admin role": "lower(coalesce(p.role, '')) = 'admin'" in sql,
    "helper execution not public/anon": "revoke all on function public.is_active_admin_profile() from public, anon" in sql,
    "KTV profile read restricted to self": "id = (select auth.uid())" in sql,
    "Admin profile read requires helper": "(select public.is_active_admin_profile())" in sql,
    "profile insert blocked": "for insert\nto authenticated\nwith check (false)" in sql,
    "profile update blocked": "for update\nto authenticated\nusing (false)\nwith check (false)" in sql,
    "profile delete blocked": "for delete\nto authenticated\nusing (false)" in sql,
    "policies are restrictive": sql.count("as restrictive") == 4,
}
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"RESULT {sum(checks.values())}/{len(checks)}")
if not all(checks.values()):
    raise SystemExit(1)
