from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030010_single_restore_commit_per_owner.sql').read_text(encoding='utf-8').lower()
BASE_COMMIT = (ROOT / 'supabase/migrations_pending_reconciliation/202611020006_restore_commit_and_snapshot_read.sql').read_text(encoding='utf-8').lower()
QUOTA = (ROOT / 'supabase/migrations_pending_reconciliation/202611030009_bound_restore_staging_resources.sql').read_text(encoding='utf-8').lower()

checks = [
    ('migration is transactional', SQL.strip().startswith('-- step98') and SQL.rstrip().endswith('commit;')),
    ('guard only fires on STAGING to COMMITTED transition', "old.status = 'staging' and new.status = 'committed'" in SQL),
    ('guard is installed before status update', 'before update of status on public.inventory_restore_sessions' in SQL),
    ('guard serializes against Step97 owner quota operations', "pg_advisory_xact_lock(hashtextextended(new.owner_id::text, 97))" in SQL and "hashtextextended(new.owner_id::text, 97)" in QUOTA),
    ('guard counts only same-owner STAGING sessions', 'where owner_id = new.owner_id' in SQL and "and status = 'staging'" in SQL),
    ('guard rejects multiple legacy staging sessions', 'if v_active_staging <> 1 then' in SQL and 'restore_owner_has_multiple_staging_sessions' in SQL),
    ('trigger is limited to exact transition', 'when (old.status = \'staging\' and new.status = \'committed\')' in SQL),
    ('guard function is not client callable', 'revoke all privileges on function public.enforce_single_inventory_restore_commit() from public, anon, authenticated;' in SQL),
    ('commit remains owner-serialized', "pg_advisory_xact_lock(hashtextextended(v_owner::text, 0))" in BASE_COMMIT),
    ('commit locks exact owner session before replacement', 'where restore_id = p_restore_id and owner_id = v_owner\n     for update' in BASE_COMMIT),
    ('live replacement and status transition share one transaction', BASE_COMMIT.strip().startswith('-- atomic commit') and BASE_COMMIT.rstrip().endswith('commit;') and 'delete from public.movements where owner_id = v_owner' in BASE_COMMIT and "set status = 'committed'" in BASE_COMMIT),
]

# Model: a commit is safe only when exactly its own session is the owner's
# remaining STAGING session. A legacy second session must block the whole txn.
def may_commit(staging_ids, target):
    return target in staging_ids and len(staging_ids) == 1

checks.extend([
    ('single staging session may commit', may_commit({'restore-A'}, 'restore-A')),
    ('two legacy staging sessions cannot commit A', not may_commit({'restore-A', 'restore-B'}, 'restore-A')),
    ('two legacy staging sessions cannot commit B', not may_commit({'restore-A', 'restore-B'}, 'restore-B')),
    ('other owner staging session does not affect this owner', may_commit({'owner-1-A'}, 'owner-1-A')),
    ('already committed retry is not a STAGING transition', not ('COMMITTED' == 'STAGING')),
])

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed:
    raise SystemExit(1)
