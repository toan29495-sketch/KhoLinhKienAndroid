from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
UI = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
LEDGER = (ROOT / 'supabase/migrations_pending_reconciliation/202611020001_offline_sync_foundation.sql').read_text()
MUTATION = (ROOT / 'supabase/migrations_pending_reconciliation/202611030011_fence_inventory_mutations_against_restore.sql').read_text()
OWNER_GUARD = (ROOT / 'supabase/migrations_pending_reconciliation/202611030007_lock_down_part_image_storage_scope.sql').read_text()
OWNER_POLICY = (ROOT / 'supabase/migrations_pending_reconciliation/202611020002_part_image_owner_policies.sql').read_text()
LEDGER_LOCK = (ROOT / 'supabase/migrations_pending_reconciliation/202611030006_lock_down_sync_operation_ledger.sql').read_text()
FUNCTIONS = ROOT / 'supabase/functions'

checks = {
    'PART_DELETE payload snapshots the previous photo_path': '.put("photo_path", part.photoPath)' in DATA and 'operationType = "PART_DELETE"' in DATA,
    'delete enqueues immutable Outbox row before removing local Part in one transaction': DATA.index('enqueue(SyncOutbox(ownerId = ownerId, operationType = "PART_DELETE"') < DATA.index('deletePartRow(ownerId, code)'),
    'UI explicitly preserves movement history after Part deletion': 'Lịch sử giao dịch vẫn được giữ' in UI,
    'delete acknowledgement refuses while another unresolved operation exists': 'countOtherUnresolvedPartOperations(ownerId, entityId, operationId) == 0' in DATA,
    'local pending photo is deleted only after exact Room acknowledgement': SENDER.index('check(markedSynced)') < SENDER.index('File(path).delete()'),
    'local pending photo remains while another unresolved Outbox row references it': 'hasPendingPhotoReference(ownerId, path, claimed.operationId)' in SENDER and "status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED')" in DATA,
    'local file deletion is best-effort and cannot roll back successful sync': 'runCatching { File(path).delete() }' in SENDER,
    'RPC failure handler does not execute local photo deletion': 'catch (error: Exception)' in SENDER and 'File(path).delete()' not in SENDER[SENDER.index('catch (error: Exception)'):SENDER.index('return@withLock SyncSendResult.Partial', SENDER.index('catch (error: Exception)'))],
    'client never invokes Storage remove for cloud photos': '.storage.from("part-images").remove' not in SENDER,
    'UI never invokes a cloud Storage deletion method': '.storage.from("part-images").remove' not in UI and 'deleteAuthenticated' not in UI,
    'no Edge Function currently implements photo garbage collection': not any(('storage.from("part-images").remove' in p.read_text(errors='ignore') or 'storage.objects' in p.read_text(errors='ignore')) for p in FUNCTIONS.rglob('*') if p.is_file()),
    'restrictive Storage owner guard covers all operations': 'as restrictive' in OWNER_GUARD and 'for all' in OWNER_GUARD and 'storage.foldername(name)' in OWNER_GUARD,
    'Storage owner guard also requires active profile': 'public.is_active_profile()' in OWNER_GUARD,
    'authenticated Storage delete policy is owner-folder scoped': 'for delete' in OWNER_POLICY and 'to authenticated' in OWNER_POLICY and "(storage.foldername(name))[1] = (select auth.uid())::text" in OWNER_POLICY,
    'idempotency ledger persists original operation payload': 'add column if not exists payload jsonb' in LEDGER and 'v_existing.payload is distinct from p_payload' in MUTATION,
    'append-only sync change feed stores payload snapshots': 'create table if not exists public.inventory_sync_events' in LEDGER and 'payload jsonb not null' in LEDGER,
    'sync operation ledger is not directly accessible to authenticated clients': 'revoke all privileges on table public.sync_operations from public, anon, authenticated' in LEDGER_LOCK,
    'no SQL migration deletes Storage objects directly': not any('delete from storage.objects' in p.read_text(errors='ignore').lower() for p in (ROOT / 'supabase').rglob('*.sql')),
    'upload owner is checked after hashing and immediately before Storage upload': SENDER.index('val expectedDigest = PhotoIntegrity.sha256(photoBytes)') < SENDER.index('requireActiveOwner(ownerId)', SENDER.index('val expectedDigest = PhotoIntegrity.sha256(photoBytes)')) < SENDER.index('client.storage.from("part-images").upload'),
}

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP128_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
