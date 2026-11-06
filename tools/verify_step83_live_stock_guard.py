from pathlib import Path

root = Path(__file__).resolve().parents[1]
data = (root / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
start = data.index('suspend fun changeStock(')
end = data.index('\n    }\n}', start)
body = data[start:end]
checks = {
    'live stock mutation explicitly allowlists NHAP XUAT KIEM_KE': 'type in setOf("NHẬP", "XUẤT", "KIỂM KÊ")' in body,
    'opening balance remains restricted to initial part creation': 'opening balance is created only by insertPartWithOpeningMovement()' in body,
    'negative pre-existing stock fails closed': 'require(part.quantity >= 0)' in body,
    'quantity arithmetic uses Long before narrowing': 'part.quantity.toLong() + amount.toLong()' in body and 'part.quantity.toLong() - amount.toLong()' in body,
    'result is bounded to Room Int range': 'afterLong in 0L..Int.MAX_VALUE.toLong()' in body,
    'narrowing occurs only after range validation': body.index('afterLong in 0L..Int.MAX_VALUE.toLong()') < body.index('val after = afterLong.toInt()'),
    'movement and outbox remain in same @Transaction function': '@Transaction\n    suspend fun changeStock(' in data and 'enqueue(SyncOutbox(ownerId = ownerId, operationType = "STOCK_CHANGE"' in body,
}

def apply(q, amount, kind):
    if kind not in {'NHẬP', 'XUẤT', 'KIỂM KÊ'}: raise ValueError('unsupported')
    if q < 0: raise ValueError('bad current stock')
    if (amount < 0 if kind == 'KIỂM KÊ' else amount <= 0): raise ValueError('bad amount')
    value = q + amount if kind == 'NHẬP' else q - amount if kind == 'XUẤT' else amount
    if value < 0 or value > 2**31 - 1: raise ValueError('out of range')
    return value

cases = [
    ('valid inbound', (10, 5, 'NHẬP'), 15),
    ('valid outbound', (10, 4, 'XUẤT'), 6),
    ('valid zero stocktake', (10, 0, 'KIỂM KÊ'), 0),
]
for name, args, expected in cases:
    try: checks[f'model: {name}'] = apply(*args) == expected
    except Exception: checks[f'model: {name}'] = False
for name, args in [
    ('unknown type cannot masquerade as stocktake', (10, 3, 'OTHER')),
    ('opening balance cannot be entered as live stock change', (0, 3, 'ĐẦU KỲ')),
    ('negative current stock rejected', (-1, 1, 'NHẬP')),
    ('outbound below zero rejected', (2, 3, 'XUẤT')),
    ('inbound Int overflow rejected', (2**31 - 1, 1, 'NHẬP')),
    ('negative stocktake rejected', (10, -1, 'KIỂM KÊ')),
]:
    try: apply(*args); rejected = False
    except ValueError: rejected = True
    checks[f'model: {name}'] = rejected

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP83_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()): raise SystemExit(1)
