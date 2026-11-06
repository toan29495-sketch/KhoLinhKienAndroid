from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
repo = ROOT / 'app/src/main/java/vn/kholinhkien'
auth = (repo / 'AuthRepository.kt').read_text()
admin = (repo / 'AdminScreen.kt').read_text()
checks = {
    'audit model maps actor and target usernames': '@SerialName("actor_username") val actorUsername' in auth and '@SerialName("target_username") val targetUsername' in auth,
    'audit model maps status and timestamps': 'val status: String' in auth and '@SerialName("created_at") val createdAt' in auth and '@SerialName("finished_at") val finishedAt' in auth,
    'RPC limit is bounded 1..200': 'require(limit in 1..200)' in auth,
    'uses existing guarded audit RPC': 'rpc("list_admin_audit_events"' in auth and 'put("p_limit", limit)' in auth,
    'RPC response decoded as complete JSON array': 'Json.parseToJsonElement(raw.toString()).jsonArray' in auth and 'decodeFromJsonElement<AdminAuditEvent>' in auth,
    'Admin UI exposes audit panel': '"Nhật ký quản trị"' in admin and 'showAudit' in admin,
    'audit panel refresh is guarded': 'if (auditLoading || actionBusy) return' in admin,
    'audit panel handles cancellation': 'catch (cancelled: CancellationException)' in admin,
    'audit panel shows STARTED/SUCCEEDED/FAILED status': 'event.status' in admin and '"SUCCEEDED"' in admin and '"FAILED"' in admin,
    'audit error code and timestamps are displayed': 'event.errorCode' in admin and 'event.createdAt' in admin and 'event.finishedAt' in admin,
    'KTV mutation busy guard remains': 'actionBusy' in admin and 'repository.resetKtvPassword' in admin,
    'Admin own warehouse separation text remains': 'không gộp linh kiện giữa các kho' in admin,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"{len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
