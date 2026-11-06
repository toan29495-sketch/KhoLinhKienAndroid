#!/usr/bin/env python3
"""Static contract checks and a small reader/writer-lock interleaving model."""
from pathlib import Path
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030011_fence_inventory_mutations_against_restore.sql').read_text()
RESTORE = (ROOT / 'supabase/migrations_pending_reconciliation/202611020006_restore_commit_and_snapshot_read.sql').read_text()
LEGACY = (ROOT / 'supabase/migrations_pending_reconciliation/202611020004_restore_inventory_snapshot.sql').read_text()
BASE = (ROOT / 'supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql').read_text()

checks = []
def check(name, cond):
    checks.append((name, bool(cond)))

check('replaces exact inventory RPC signature', 'create or replace function public.apply_inventory_operation(' in SQL)
check('ordinary mutations acquire shared transaction advisory lock', 'pg_advisory_xact_lock_shared(hashtextextended(v_owner::text, 0))' in SQL)
check('shared lock occurs after auth and active-profile validation', SQL.index('if not public.is_active_profile()') < SQL.index('pg_advisory_xact_lock_shared'))
check('restore staging commit uses matching exclusive lock key', 'pg_advisory_xact_lock(hashtextextended(v_owner::text, 0))' in RESTORE)
check('legacy restore uses matching exclusive lock key', 'pg_advisory_xact_lock(hashtextextended(v_owner::text, 0))' in LEGACY)
check('same RPC retains row-level stock lock', 'for update;' in SQL.lower())
check('same RPC retains optimistic server-version check', 'v_part.server_version is distinct from v_expected_server_version' in SQL)
check('same RPC retains operation idempotency payload check', 'v_existing.payload is distinct from p_payload' in SQL)
check('same RPC retains authenticated-only execute grant', 'grant execute on function public.apply_inventory_operation(uuid, text, uuid, jsonb) to authenticated;' in SQL)
def normalized_sql(value):
    return ' '.join(line.strip() for line in value.splitlines()
                    if line.strip() and not line.lstrip().startswith('--')
                    and 'pg_advisory_xact_lock_shared(hashtextextended(v_owner::text, 0));' not in line)
check('same RPC body otherwise derives from Step75 CAS baseline', normalized_sql(SQL) == normalized_sql(BASE))

# Model PostgreSQL transaction-scoped shared/exclusive coordination: readers are
# ordinary mutations; writer is a restore. The writer must wait for active readers,
# and new readers must wait while the writer owns the exclusive lock.
class RWLock:
    def __init__(self):
        self.cv = threading.Condition()
        self.readers = 0
        self.writer = False
        self.waiting_writers = 0
    def shared(self):
        lock = self
        class C:
            def __enter__(self):
                with lock.cv:
                    while lock.writer or lock.waiting_writers:
                        lock.cv.wait()
                    lock.readers += 1
            def __exit__(self, *_):
                with lock.cv:
                    lock.readers -= 1
                    lock.cv.notify_all()
        return C()
    def exclusive(self):
        lock = self
        class C:
            def __enter__(self):
                with lock.cv:
                    lock.waiting_writers += 1
                    while lock.writer or lock.readers:
                        lock.cv.wait()
                    lock.waiting_writers -= 1
                    lock.writer = True
            def __exit__(self, *_):
                with lock.cv:
                    lock.writer = False
                    lock.cv.notify_all()
        return C()

rw = RWLock()
mutation_entered = threading.Event()
release_mutation = threading.Event()
restore_entered = threading.Event()
late_mutation_entered = threading.Event()

def mutation():
    with rw.shared():
        mutation_entered.set()
        release_mutation.wait(2)

def restore():
    with rw.exclusive():
        restore_entered.set()
        time.sleep(0.03)

def late_mutation():
    with rw.shared():
        late_mutation_entered.set()

m = threading.Thread(target=mutation); m.start(); assert mutation_entered.wait(1)
r = threading.Thread(target=restore); r.start(); time.sleep(0.03)
check('restore waits for mutation already in flight', not restore_entered.is_set())
l = threading.Thread(target=late_mutation); l.start(); time.sleep(0.03)
check('new mutation cannot enter while restore is waiting', not late_mutation_entered.is_set())
release_mutation.set(); m.join(1); r.join(1); l.join(1)
check('restore completes before later mutation enters', restore_entered.is_set() and late_mutation_entered.is_set())

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
print(f'{sum(ok for _, ok in checks)}/{len(checks)} passed')
if not all(ok for _, ok in checks):
    raise SystemExit(1)
