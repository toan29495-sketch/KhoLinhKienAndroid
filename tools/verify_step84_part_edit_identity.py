from pathlib import Path

root = Path(__file__).resolve().parents[1]
data = (root / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
ui = (root / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
start = data.index('suspend fun update(ownerId: String, part: Part)')
end = data.index('\n    }', start) + 6
body = data[start:end]
checks = {
    'edit API requires explicit authenticated owner': 'suspend fun update(ownerId: String, part: Part)' in data and 'part.ownerId == ownerId' in body,
    'edit refuses missing or foreign-owned row': 'byId(ownerId, part.id)' in body and 'Không tìm thấy linh kiện thuộc tài khoản hiện tại' in body,
    'stable sync identity and QR code cannot be changed': 'part.syncId == previous.syncId && part.code == previous.code' in body,
    'server version cannot be forged by edit': 'part.serverVersion == previous.serverVersion' in body,
    'quantity cannot bypass movement history': 'part.quantity == previous.quantity' in body and 'Nhập, Xuất hoặc Kiểm kê' in body,
    'metadata fields validated': 'part.name.isNotBlank()' in body and 'part.minQuantity >= 0' in body and 'part.unit.isNotBlank()' in body,
    'tracking mode is allowlisted': 'part.trackingMode == "SINGLE" || part.trackingMode == "BULK"' in body,
    'BULK to SINGLE blocked while quantity exceeds one': 'previous.trackingMode != "BULK" || part.trackingMode != "SINGLE" || previous.quantity <= 1' in body,
    'row update must affect exactly one row': 'check(updateRow(part) == 1)' in body,
    'edit remains atomic with outbox enqueue': '@Transaction\n    suspend fun update(ownerId: String, part: Part)' in data and 'enqueuePart(ownerId, "PART_UPSERT", part, previous.photoPath)' in body,
    'UI passes authenticated profile owner': 'dao.update(profile.id, updated.copy(' in ui,
}

def validate(old, new):
    if old['owner'] != new['owner']: raise ValueError('owner')
    for key in ('sync_id', 'code', 'server_version', 'quantity'):
        if old[key] != new[key]: raise ValueError(key)
    if not new['name'].strip() or new['min'] < 0 or not new['unit'].strip(): raise ValueError('metadata')
    if new['mode'] not in {'SINGLE', 'BULK'}: raise ValueError('mode')
    if old['mode'] == 'BULK' and new['mode'] == 'SINGLE' and old['quantity'] > 1: raise ValueError('mode_transition')

base = dict(owner='ktv-a', sync_id='uuid-a', code='QR-A', server_version=7, quantity=3, name='Part', min=0, unit='cái', mode='BULK')
valid = dict(base, name='Part updated', min=2)
try:
    validate(base, valid); checks['model: valid metadata edit accepted'] = True
except ValueError: checks['model: valid metadata edit accepted'] = False
for label, changes in [
    ('foreign owner rejected', {'owner':'ktv-b'}),
    ('sync identity change rejected', {'sync_id':'uuid-b'}),
    ('QR code change rejected', {'code':'QR-B'}),
    ('server version change rejected', {'server_version':8}),
    ('direct stock edit rejected', {'quantity':2}),
    ('negative minimum rejected', {'min':-1}),
    ('invalid mode rejected', {'mode':'OTHER'}),
    ('BULK to SINGLE with multiple stock rejected', {'mode':'SINGLE'}),
]:
    try: validate(base, dict(base, **changes)); rejected = False
    except ValueError: rejected = True
    checks[f'model: {label}'] = rejected

for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP84_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()): raise SystemExit(1)
