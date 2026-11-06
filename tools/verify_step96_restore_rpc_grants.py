from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIG = ROOT / "supabase/migrations_pending_reconciliation/202611030008_harden_restore_rpc_execute_scope.sql"
TEXT = MIG.read_text(encoding="utf-8").lower()

FUNCTIONS = [
    ("begin_inventory_restore", "uuid, text, integer, integer, integer, integer"),
    ("stage_inventory_restore_chunk", "uuid, text, integer, jsonb"),
    ("commit_inventory_restore", "uuid"),
    ("read_inventory_restore_snapshot_chunk", "uuid, text, integer"),
    ("verify_inventory_restore_snapshot_chunk", "uuid, text, integer, jsonb"),
    ("pull_admin_movement_changes", "bigint, integer"),
]

checks = []
for name, signature in FUNCTIONS:
    needle = f"function public.{name}({signature})"
    count_revoke = TEXT.count(f"revoke all privileges on {needle}")
    count_grant = TEXT.count(f"grant execute on {needle}")
    checks.append((f"{name}: one explicit revoke", count_revoke == 1))
    checks.append((f"{name}: one authenticated grant", count_grant == 1))
    # Every revoke statement must name all client-facing roles.
    pos = TEXT.find(f"revoke all privileges on {needle}")
    if pos >= 0:
        end = TEXT.find(";", pos)
        stmt = TEXT[pos:end]
        checks.append((f"{name}: revoke PUBLIC/anon/authenticated", all(x in stmt for x in ("public", "anon", "authenticated"))))
    else:
        checks.append((f"{name}: revoke PUBLIC/anon/authenticated", False))

checks.extend([
    ("migration is transactional", TEXT.strip().startswith("-- step96") and "begin;" in TEXT and TEXT.rstrip().endswith("commit;")),
    ("restore legacy one-shot RPC is not reintroduced", "create function public.restore_inventory_snapshot" not in TEXT),
    ("scope remains authenticated-only", TEXT.count("\n    to authenticated;") == len(FUNCTIONS)),
])

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
print(f"RESULT: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
