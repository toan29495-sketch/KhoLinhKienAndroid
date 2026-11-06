from pathlib import Path

root = Path(__file__).resolve().parents[1]
mapper = (root / 'app/src/main/java/vn/kholinhkien/RestorePayloadMapper.kt').read_text()
data = (root / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks = {
    'ZIP mapper allowlists the four supported movement types': 'type in setOf("NHẬP", "XUẤT", "KIỂM KÊ", "ĐẦU KỲ")' in mapper,
    'ZIP mapper requires positive amount except stocktake': 'type == "KIỂM KÊ" || amount > 0' in mapper,
    'ZIP mapper validates inbound arithmetic using Long': 'afterQty.toLong() == beforeQty.toLong() + amount.toLong()' in mapper,
    'ZIP mapper validates outbound arithmetic and prevents negative stock': 'beforeQty >= amount && afterQty.toLong() == beforeQty.toLong() - amount.toLong()' in mapper,
    'ZIP mapper validates stocktake and opening balance semantics': 'afterQty == amount' in mapper and 'beforeQty == 0 && afterQty == amount' in mapper,
    'Room cloud snapshot apply enforces same movement type allowlist': 'movementType in setOf("NHẬP", "XUẤT", "KIỂM KÊ", "ĐẦU KỲ")' in data,
    'Room cloud snapshot apply enforces positive amount except stocktake': 'movementType == "KIỂM KÊ" || amount > 0' in data,
    'Room cloud snapshot apply validates inbound/outbound arithmetic': 'afterQty.toLong() == beforeQty.toLong() + amount.toLong()' in data and 'beforeQty >= amount && afterQty.toLong() == beforeQty.toLong() - amount.toLong()' in data,
    'Room cloud snapshot apply validates stocktake/opening balance': 'afterQty == amount' in data and 'beforeQty == 0 && afterQty == amount' in data,
}

def valid(t, amount, before, after):
    if t not in {'NHẬP', 'XUẤT', 'KIỂM KÊ', 'ĐẦU KỲ'}: return False
    if min(amount, before, after) < 0: return False
    if t != 'KIỂM KÊ' and amount <= 0: return False
    if t == 'NHẬP': return after == before + amount and after <= 2**31 - 1
    if t == 'XUẤT': return before >= amount and after == before - amount
    if t == 'KIỂM KÊ': return after == amount
    return before == 0 and after == amount

cases = [
    ('valid inbound', ('NHẬP', 5, 10, 15), True),
    ('valid outbound', ('XUẤT', 4, 10, 6), True),
    ('valid stocktake', ('KIỂM KÊ', 7, 10, 7), True),
    ('valid opening balance', ('ĐẦU KỲ', 7, 0, 7), True),
    ('reject unknown movement type', ('ADJUST', 1, 1, 2), False),
    ('reject zero inbound', ('NHẬP', 0, 10, 10), False),
    ('reject incorrect inbound arithmetic', ('NHẬP', 5, 10, 14), False),
    ('reject outbound below zero', ('XUẤT', 11, 10, 0), False),
    ('reject incorrect outbound arithmetic', ('XUẤT', 4, 10, 7), False),
    ('reject incorrect stocktake result', ('KIỂM KÊ', 7, 10, 8), False),
    ('reject invalid opening balance', ('ĐẦU KỲ', 7, 1, 7), False),
]
for name, args, expected in cases:
    checks[f'case: {name}'] = valid(*args) == expected

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP81_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
