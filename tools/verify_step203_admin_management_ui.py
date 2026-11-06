#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
ui = (ROOT / "app/src/main/java/vn/kholinhkien/AdminScreen.kt").read_text(encoding="utf-8")
main = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
auth = (ROOT / "app/src/main/java/vn/kholinhkien/AuthRepository.kt").read_text(encoding="utf-8")
checks=[]
def check(label, condition): checks.append((label,bool(condition)))
check("Admin screen is wired only in Admin role branch", '3 -> if (isAdmin) AdminScreen' in main)
check("screen lists KTV accounts with active/locked state", 'repository.getKtvUsers()' in ui and 'user.isActive' in ui and 'Đã khóa' in ui)
check("screen exposes create, reset, lock and unlock actions", all(x in ui for x in ["Tạo tài khoản KTV", "Đặt lại mật khẩu", '"reset_password"', '"lock"', '"unlock"']))
check("create dialog validates normalized ASCII username length", 'Regex("^[a-z0-9_-]{3,32}$")' in ui and "validUsername" in ui)
check("create dialog bounds display name", "normalizedDisplayName.length <= 80" in ui)
check("create UI explains temporary password and forced change", 'Mật khẩu ban đầu: 000000' in ui and 'KTV phải đổi mật khẩu khi đăng nhập lần đầu' in ui)
check("create uses normalized values and awaits refreshed list", "repository.createKtv(normalizedUsername, normalizedDisplayName)" in ui and "loadUsers()" in ui)
check("all account mutations share an in-flight guard", "var actionBusy by remember" in ui and "if (!actionBusy) scope.launch" in ui)
check("refresh and sign-out are disabled during mutation", "enabled = !loading && !actionBusy" in ui and "enabled = !actionBusy" in ui)
check("reset/lock/unlock require explicit confirmation", "pendingAction?.let" in ui and "Xác nhận" in ui and "pendingAction = null" in ui)
check("failed action keeps confirmation available for retry", ui.index('catch (e: Exception)') < ui.index('finally {\n                            actionBusy = false') and 'pendingAction = null' in ui)
check("cancellation propagates instead of being swallowed", "catch (cancelled: CancellationException)" in ui)
check("list refresh surfaces backend errors", 'error = e.message ?: "Không tải được danh sách KTV"' in ui and 'color = MaterialTheme.colorScheme.error' in ui)
check("Admin UI explains that the Admin's warehouse remains separate", "không gộp linh kiện giữa các kho" in ui)
check("Admin's inventory remains in the normal owner-scoped inventory branch", 'ToolsScreen(' in main and 'profile.id' in main)
failed=[label for label,ok in checks if not ok]
for label,ok in checks: print(("PASS" if ok else "FAIL")+" | "+label)
print(f"STEP203: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed: raise SystemExit(1)
