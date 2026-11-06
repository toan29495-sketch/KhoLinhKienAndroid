from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
progress = (ROOT / 'PROGRESS.md').read_text(encoding='utf-8')
audit = (ROOT / 'SECURITY_DEFINER_AUDIT_20261104.md').read_text(encoding='utf-8')
review = (ROOT / 'STEP207_REVIEW.md').read_text(encoding='utf-8')
manifest = (ROOT / 'supabase/REMOTE_MIGRATION_MANIFEST.md').read_text(encoding='utf-8')
pending = ROOT / 'supabase/migrations_pending_reconciliation'
checks = {
    'Step207 progress entry exists': '## Step207 — SECURITY DEFINER static review status and migration evidence reconciliation' in progress,
    'all 20 authenticated RPC static review documented': 'The 20 authenticated-callable functions were individually retrieved' in audit,
    'runtime adversarial tests remain explicitly outstanding': 'Authenticated runtime tests, malicious-input/fault-injection tests, and concurrency tests remain outstanding.' in audit,
    'backup byte-hash trust caveat retained': "not the bytes' SHA-256 independently" in audit,
    'Step139 absence in current migration history explicit': 'none of the nine Step139 version strings above appeared in `list_migrations`' in manifest,
    'no speculative one-to-one mapping claimed': 'do not establish a reliable one-to-one mapping' in review,
    'no deployment or production mutation claimed': 'No Supabase SQL, migration ledger, schema, grants, RLS policies' in review,
    'pending SQL evidence set remains present': len(list(pending.glob('*.sql'))) == 25,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
