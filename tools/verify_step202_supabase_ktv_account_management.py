#!/usr/bin/env python3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
fn = (ROOT / "supabase/functions/admin-users/index.ts").read_text(encoding="utf-8")
auth = (ROOT / "app/src/main/java/vn/kholinhkien/AuthRepository.kt").read_text(encoding="utf-8")
profile = (ROOT / "app/src/main/java/vn/kholinhkien/UserProfile.kt").read_text(encoding="utf-8")
checks = []
def check(label, condition): checks.append((label, bool(condition)))
check("function requires POST and handles preflight", 'req.method === "OPTIONS"' in fn and 'req.method !== "POST"' in fn)
check("function requires a bearer token and validates it with Auth", "Bearer\\s+(.+)" in fn and "callerClient.auth.getUser(tokenMatch[1])" in fn)
check("function checks caller active Admin profile", 'select("id, username, role, is_active, must_change_password")' in fn and 'callerProfile.must_change_password === true' in fn and 'callerProfile.role !== "admin"' in fn and 'callerProfile.is_active !== true' in fn)
check("service-role bypass is compensated by immutable Admin link check", 'from("app_admins")' in fn and 'select("user_id")' in fn and 'if (!adminLink)' in fn)
check("only four supported account actions are accepted", '"create", "reset_password", "lock", "unlock"' in fn)
check("request rejects unexpected fields", "allowedKeys" in fn and "some((key) => !allowedKeys.has(key))" in fn)
check("username is normalized and ASCII constrained", '/^[a-z0-9_-]{3,32}$/' in fn)
check("create provisions confirmed KTV with forced password change", 'password: "000000"' in fn and 'email_confirm: true' in fn and 'must_change_password: true' in fn and 'role: "ktv"' in fn)
check("create updates trigger-created profile instead of duplicate insert", '.from("profiles")' in fn and '.update({' in fn and 'auth.admin.deleteUser(created.user.id)' in fn)
check("reset sets forced-change flag before Auth password update", fn.index('must_change_password: true') < fn.index('auth.admin.updateUserById(target.id, { password: "000000" })'))
check("lock and unlock update only KTV active state", 'target.role !== "ktv"' in fn and 'is_active: action === "unlock"' in fn)
check("client exposes create/reset/lock/unlock operations", all(x in auth for x in ["createKtv", "resetKtvPassword", "lockKtv", "unlockKtv"]))
check("client reads KTV list through profiles role filter", 'from("profiles").select' in auth and 'eq("role", "ktv")' in auth)
check("profile model carries role, active and forced-password fields", all(x in profile for x in ['val role: String', 'must_change_password', 'is_active']))
failed=[label for label,ok in checks if not ok]
for label,ok in checks: print(("PASS" if ok else "FAIL")+" | "+label)
print(f"STEP202: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed: raise SystemExit(1)
