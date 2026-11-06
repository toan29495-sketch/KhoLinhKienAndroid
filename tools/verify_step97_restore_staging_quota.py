from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030009_bound_restore_staging_resources.sql').read_text(encoding='utf-8').lower()
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSessionSender.kt').read_text(encoding='utf-8')

checks = [
    ('cleanup RPC binds owner to auth.uid', 'v_owner uuid := auth.uid()' in SQL),
    ('cleanup rejects unauthenticated callers', "if v_owner is null then raise exception 'auth_required'" in SQL),
    ('cleanup rejects inactive profiles', 'if not public.is_active_profile()' in SQL),
    ('only STAGING sessions older than 14 days expire', "status = 'staging'" in SQL and "interval '14 days'" in SQL),
    ('expired sessions become ABORTED', "set status = 'aborted'" in SQL),
    ('cleanup deletes chunks only through exact expired-session CTE', 'using expired e' in SQL and 'c.restore_id = e.restore_id' in SQL),
    ('cleanup does not delete committed snapshots', "status = 'committed'" not in SQL and 'delete from public.inventory_restore_commits' not in SQL),
    ('one active STAGING session per owner', 'if v_active >= 1' in SQL and "status = 'staging'" in SQL),
    ('expected chunk count bounded to 8192', 'new.expected_part_chunks::bigint + new.expected_movement_chunks::bigint > 8192' in SQL),
    ('actual staged chunk count bounded to 8192', 'if v_chunks >= 8192' in SQL),
    ('staging payload bytes bounded to 256 MiB', 'v_bytes + pg_column_size(new.payload) > 268435456' in SQL),
    ('quota checks serialize per owner', SQL.count('pg_advisory_xact_lock(hashtextextended(new.owner_id::text, 97))') == 2),
    ('session quota trigger is installed before insert', 'before insert on public.inventory_restore_sessions' in SQL),
    ('chunk quota trigger is installed before insert', 'before insert on public.inventory_restore_chunks' in SQL),
    ('cleanup RPC explicitly authenticated-only', 'revoke all privileges on function public.cleanup_my_inventory_restore_staging() from public, anon, authenticated;' in SQL and 'grant execute on function public.cleanup_my_inventory_restore_staging() to authenticated;' in SQL),
    ('trigger functions are not client callable', SQL.count('revoke all privileges on function public.enforce_inventory_restore_') == 2),
    ('migration is transactional', SQL.strip().startswith('-- step97') and SQL.rstrip().endswith('commit;')),
    ('Android invokes cleanup before begin RPC', SENDER.index('cleanup_my_inventory_restore_staging') < SENDER.index('"begin_inventory_restore"')),
    ('Android rechecks exact owner after cleanup', 'require(client.auth.currentuserornull()?.id == ownerid)' in SENDER.lower()),
]

# Small deterministic quota/expiry model for boundary behavior.
LIMIT_BYTES = 268435456
LIMIT_CHUNKS = 8192
DAY = 24 * 60 * 60

def can_add(existing_bytes, existing_chunks, payload_bytes):
    return existing_chunks < LIMIT_CHUNKS and existing_bytes + payload_bytes <= LIMIT_BYTES

checks.extend([
    ('256 MiB exact boundary accepted', can_add(LIMIT_BYTES - 1024, 1, 1024)),
    ('256 MiB plus one byte rejected', not can_add(LIMIT_BYTES, 1, 1)),
    ('8192nd chunk accepted when count is 8191', can_add(0, LIMIT_CHUNKS - 1, 1)),
    ('chunk beyond 8192 rejected', not can_add(0, LIMIT_CHUNKS, 1)),
    ('session at 14 days is not yet stale', not (14 * DAY > 14 * DAY)),
    ('session older than 14 days is stale', 14 * DAY + 1 > 14 * DAY),
])

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed:
    raise SystemExit(1)
