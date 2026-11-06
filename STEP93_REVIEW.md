# Step93 – Lock down direct parts/movements owner access (2026-11-03)

## Scope
Audit direct PostgREST access to `public.parts` and `public.movements`, following Step92 profile RLS hardening.

## Finding
The reconstructed migration set contains owner predicates inside SECURITY DEFINER sync/restore RPCs and a restrictive active-profile policy, but it does not contain the original/base migrations that created `parts` and `movements` or their permissive RLS policies. Therefore the exact existing direct-table policy set cannot be established from this archive. Relying only on UI owner filters or RPC predicates does not prove direct PostgREST access is owner-scoped.

## Change
Added additive migration `supabase/migrations/202611030005_lock_down_inventory_owner_rls.sql`:
- Explicitly enables RLS on `public.parts` and `public.movements`.
- Adds RESTRICTIVE `FOR ALL TO authenticated` policies requiring `owner_id = auth.uid()` in both `USING` and `WITH CHECK` for each table.
- Restrictive policies AND with existing permissive grants/policies; they do not grant access if the base project has no permissive policy.
- A KTV cannot read/update/delete another owner's row or insert/update a row with a forged owner_id through direct authenticated PostgREST access.
- Admin direct access is deliberately limited to Admin's own inventory. Global Admin movement history continues through `pull_admin_movement_changes()` SECURITY DEFINER RPC, which has its own NULL-safe active-Admin authorization check.
- Existing sync and restore SECURITY DEFINER RPCs retain explicit `owner_id = auth.uid()` / `v_owner` predicates. Restore deletes/replaces only the caller's rows.
- No existing migration was modified.

## Verification
- `tools/verify_step93_inventory_owner_rls.py`: 12/12 static assertions PASS.
- Re-ran `tools/verify_sync_step49_72.py`: 24/24 static checks + SQLite schema/atomic replacement smoke test PASS.
- Re-ran all Step73–93 verification scripts: all passed (Step73 5/5, 74 5/5, 75 7/7, 76 7/7, 77 7/7 + model, 78 7/7 + model, 79 12/12, 80 16/16, 81 20/20, 82 14/14, 83 16/16, 84 20/20, 85 13/13, 86 7/7, 87 10/10, 88 11/11, 89 8/8, 90 10/10, 91 10/10, 92 11/11, 93 12/12).

## Important limitations / pending validation
- Static source review only; migration has NOT been executed against PostgreSQL/Supabase staging.
- The base schema/policies for `parts` and `movements` are absent from this reconstructed archive. Before deployment, inspect actual table grants and permissive RLS policies, and confirm `owner_id` exists with the expected UUID type on both tables.
- Verify that SECURITY DEFINER RPC owners have the expected RLS bypass/table privileges, and test sync, restore, and Admin global movement feed on staging. This source review does not assume those execution privileges.
- No live direct PostgREST tests with two KTV accounts/Admin, no SQL deployment, APK build, Android device test, or GitHub push.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of the missing original source.
