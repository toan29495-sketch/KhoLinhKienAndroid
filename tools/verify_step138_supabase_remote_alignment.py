from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
active = ROOT / 'supabase/migrations'
pending = ROOT / 'supabase/migrations_pending_reconciliation'
edge = (ROOT / 'supabase/functions/admin-users/index.ts').read_text(encoding='utf-8')
foundation = (active / '20261103094931_step138_02_backend_foundation_and_security.sql').read_text(encoding='utf-8').lower()
sync = (active / '20261103095005_step138_03_inventory_sync_and_admin_feed.sql').read_text(encoding='utf-8').lower()
conflict = (active / '20261103095045_step138_04_fix_part_conflict_snapshot_id.sql').read_text(encoding='utf-8').lower()
admin = (active / '20261103095057_step138_05_require_active_admin_profile_for_admin_link.sql').read_text(encoding='utf-8').lower()
checks = {
    'seven Step138 migrations are represented in active folder': all((active / name).exists() for name in ['20261103094857_step138_01_complete_password_change.sql','20261103094931_step138_02_backend_foundation_and_security.sql','20261103095005_step138_03_inventory_sync_and_admin_feed.sql','20261103095045_step138_04_fix_part_conflict_snapshot_id.sql','20261103095057_step138_05_require_active_admin_profile_for_admin_link.sql','20261103095135_step138_06_revoke_anon_password_change_rpc.sql','20261103095727_step138_07_admin_feed_requires_admin_link.sql']),
    'three Step187 migrations remain active': all((active / name).exists() for name in ['20261103150733_step187_weekly_backup_backend.sql','20261103150823_step187_01_fix_backup_registration_ambiguity.sql','20261103151110_step187_02_deny_direct_backup_metadata_access.sql']),
    'original pending migration chain is preserved for review': len(list(pending.glob('*.sql'))) == 25,
    'correct private image bucket is provisioned without deleting legacy bucket': "values('part-images','part-images',false" in foundation,
    'sync operation ledger has payload/result/processed timestamp': all(x in foundation for x in ['add column if not exists payload jsonb','add column if not exists result jsonb','add column if not exists processed_at timestamptz']),
    'append-only event feed is owner-keyed and RLS protected': 'inventory_sync_events_owner_event_idx' in foundation and 'enable row level security' in foundation,
    'sync event table is not directly granted to clients': 'revoke all privileges on table public.inventory_sync_events from public,anon,authenticated' in foundation,
    'active profile restrictive guard is installed': 'create policy require_active_profile' in foundation and 'as restrictive' in foundation,
    'profile writes are blocked from authenticated clients': all(x in foundation for x in ['profiles_block_client_insert','profiles_block_client_update','profiles_block_client_delete']),
    'parts and movements remain owner-scoped for direct access': 'parts_owner_scope_authenticated' in foundation and 'movements_owner_scope_authenticated' in foundation,
    'part image bucket is owner-folder scoped and private': 'part_images_owner_scope_guard' in foundation and 'storage.foldername(name)' in foundation,
    'authenticated clients cannot delete part images': 'part_images_no_authenticated_delete' in foundation,
    'soft-deleted QR codes release their code via partial unique index': 'parts_owner_active_code_uidx' in foundation and 'where deleted_at is null' in foundation,
    'trigger-only functions are not client-callable': 'revoke all privileges on function public.handle_new_user() from public,anon,authenticated' in foundation and 'revoke all privileges on function public.rls_auto_enable() from public,anon,authenticated' in foundation,
    'inventory mutation RPC binds authenticated owner and active profile': 'auth.uid()' in sync and 'is_active_profile()' in sync,
    'inventory mutation RPC uses CAS and shared restore fence': 'pg_advisory_xact_lock_shared' in sync and 'expected_server_version' in sync and 'part_version_conflict' in sync,
    'inventory mutation RPC records idempotency result': 'operation_payload_conflict' in sync and 'processed_at=now()' in sync,
    'owner feed is cursor-based and owner-scoped': 'where owner_id=v_owner and event_id>p_after_event_id' in sync,
    'Admin feed requires active Admin role and redacts PART payloads': "lower(v_role)<>'admin'" in sync and "then 'skip'" in sync,
    'latest Admin feed additionally requires app_admins link': 'not public.is_app_admin()' in (active / '20261103095727_step138_07_admin_feed_requires_admin_link.sql').read_text(encoding='utf-8').lower(),
    'event cursor allocation is serialized before sequence allocation': 'pg_advisory_xact_lock(130130,1)' in sync and 'drop default' in sync,
    'conflict snapshot uses actual parts primary key id': 'p.id=p_part_sync_id' in conflict and 'p.sync_id' not in conflict,
    'Admin link alone cannot preserve Admin privileges after role downgrade': 'join public.profiles p on p.id=a.user_id' in admin and "lower(coalesce(p.role,''))='admin'" in admin,
    'Admin user creation updates trigger-created profile instead of duplicate insert': '.from("profiles")\n        .update({' in edge and '.from("profiles")\n        .insert({' not in edge,
    'Admin Edge Function retains server-side role and active checks': 'callerProfile.role !== "admin"' in edge and 'callerProfile.is_active !== true' in edge,
}
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"STEP138_STATIC_RESULT={sum(checks.values())}/{len(checks)}")
if not all(checks.values()):
    raise SystemExit(1)
