from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIG = ROOT / 'supabase/migrations_pending_reconciliation/202611030006_lock_down_sync_operation_ledger.sql'
APPLY = ROOT / 'supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql'
BASE = ROOT / 'supabase/migrations_pending_reconciliation/202611020001_offline_sync_foundation.sql'

def main():
    m = MIG.read_text()
    a = APPLY.read_text()
    b = BASE.read_text()
    checks = {
        'migration revokes PUBLIC': 'from public, anon, authenticated' in m.lower(),
        'ledger table is sync_operations': 'on table public.sync_operations' in m.lower(),
        'migration is transactional': 'begin;' in m.lower() and m.strip().lower().endswith('commit;'),
        'client code has no direct ledger reference': 'sync_operations' not in '\n'.join(p.read_text(errors='ignore') for p in (ROOT/'app/src/main/java').rglob('*.kt')),
        'apply RPC is SECURITY DEFINER': 'security definer' in a.lower(),
        'apply RPC binds caller': 'v_owner uuid := auth.uid()' in a,
        'apply RPC checks active profile': 'if not public.is_active_profile()' in a,
        'apply RPC compares owner and operation tuple': 'v_existing.owner_id <> v_owner' in a and 'v_existing.entity_type <> p_entity_type' in a and 'v_existing.entity_id <> p_entity_id' in a,
        'apply RPC checks immutable payload': 'v_existing.payload is distinct from p_payload' in a,
        'apply RPC replays only processed matching operation': a.index('v_existing.payload is distinct from p_payload') < a.index('if v_existing.processed_at is not null'),
        'apply RPC grants only authenticated execute': 'grant execute on function public.apply_inventory_operation(uuid, text, uuid, jsonb) to authenticated' in b.lower() or 'grant execute on function public.apply_inventory_operation(uuid, text, uuid, jsonb) to authenticated' in a.lower(),
        'migration documents owner privilege prerequisite': 'must retain its existing table privileges' in m,
    }
    for label, ok in checks.items():
        print(('PASS' if ok else 'FAIL') + ' ' + label)
    print(f'{sum(checks.values())}/{len(checks)} checks passed')
    if not all(checks.values()):
        raise SystemExit(1)

if __name__ == '__main__':
    main()
