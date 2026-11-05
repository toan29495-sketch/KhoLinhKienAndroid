from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
checks = {
    "foreground refresh checks auth session first": "if (!authRepository.isLoggedIn()) {\n                            return@launch\n                        }\n                        if (SessionExpiryPolicy.isExpired(context, profile.id))" in main,
    "camera return explanation documented": "Returning from the camera/photo picker also emits ON_START." in main,
    "session-expiry guard retained": "SessionExpiryPolicy.isExpired(context, profile.id)" in main,
    "offline data is not cleared by missing-session guard": "if (!authRepository.isLoggedIn()) {\n                            return@launch" in main,
}
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
