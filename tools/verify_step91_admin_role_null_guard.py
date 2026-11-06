from pathlib import Path
root = Path(__file__).resolve().parents[1]
sql = (root / 'supabase/migrations_pending_reconciliation/202611030003_null_safe_admin_feed_role.sql').read_text()
old = (root / 'supabase/migrations_pending_reconciliation/202611020008_admin_global_movement_feed.sql').read_text()
checks = {
    'new migration replaces the exact existing RPC': 'create or replace function public.pull_admin_movement_changes(' in sql,
    'NULL role is explicitly rejected': "if v_role is null or lower(v_role) <> 'admin' then" in sql,
    'active profile lookup remains tied to auth.uid': 'where p.id = v_user and p.is_active = true' in sql,
    'missing profile remains rejected': "if not found then\n        raise exception 'PROFILE_INACTIVE'" in sql,
    'non-admin role remains rejected': "raise exception 'ADMIN_REQUIRED' using errcode = '42501';" in sql,
    'global feed still redacts PART payloads': "then 'SKIP' else entity_type end" in sql and "then '{}'::jsonb" in sql,
    'function remains executable only by authenticated': 'revoke all on function public.pull_admin_movement_changes(bigint, integer) from public;' in sql and 'grant execute on function public.pull_admin_movement_changes(bigint, integer) to authenticated;' in sql,
    'migration is additive and transactional': sql.startswith('-- Step91:') and sql.rstrip().endswith('commit;'),
    'existing migration is preserved unchanged': old.count("if lower(v_role) <> 'admin' then") == 1,
}
# SQL three-valued-logic truth table: old IF condition was NULL for NULL role,
# and PL/pgSQL does not enter IF for NULL; new disjunction is TRUE for NULL role.
def old_reject(role):
    value = None if role is None else role.lower() != 'admin'
    return value is True

def new_reject(role):
    return role is None or role.lower() != 'admin'
checks['truth table rejects NULL, empty, KTV, and accepts Admin'] = (
    old_reject(None) is False and new_reject(None) is True and
    new_reject('') is True and new_reject('ktv') is True and
    new_reject('ADMIN') is False
)
for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'{sum(checks.values())}/{len(checks)} passed')
raise SystemExit(0 if all(checks.values()) else 1)
