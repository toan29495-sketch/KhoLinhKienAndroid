#!/usr/bin/env python3
"""Step215: avoid repeated auth refresh warnings when resuming camera/picker offline."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
source = MAIN.read_text(encoding="utf-8")

anchor = """            if (event == Lifecycle.Event.ON_START && !profileRefreshInFlight) {
                profileRefreshInFlight = true
                authScope.launch {
                    try {
                        if (SessionExpiryPolicy.isExpired(context, profile.id)) {"""
replacement = """            if (event == Lifecycle.Event.ON_START && !profileRefreshInFlight) {
                profileRefreshInFlight = true
                authScope.launch {
                    try {
                        // Returning from the camera/photo picker also emits ON_START.
                        // If there is no Supabase session, keep the cached local profile
                        // for offline inventory work and do not raise a refresh warning
                        // on every camera return. Cloud sync still requires login.
                        if (!authRepository.isLoggedIn()) {
                            return@launch
                        }
                        if (SessionExpiryPolicy.isExpired(context, profile.id)) {"""
if anchor in source:
    source = source.replace(anchor, replacement, 1)
elif "if (!authRepository.isLoggedIn()) {\n                            return@launch" not in source:
    raise SystemExit("Step215: cannot locate foreground profile refresh block")
if "Returning from the camera/photo picker also emits ON_START." not in source:
    raise SystemExit("Step215: offline camera-resume guard was not applied")

progress = ROOT / "PROGRESS.md"
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step215 — Suppress repeated auth warning on camera return (05/11/2026)"
    if heading not in log:
        log += """

## Step215 — Suppress repeated auth warning on camera return (05/11/2026)
- Root cause confirmed: returning from the Android camera/photo picker emits lifecycle ON_START, which re-runs getCurrentProfile().
- When the local app has no active Supabase session, the refresh raised "Bạn chưa đăng nhập" and displayed a warning after every camera return.
- Added an auth-session guard before the foreground profile refresh. With no active session, the app retains the cached profile and continues local/offline inventory work without repeating the warning; cloud sync still requires a valid login.
- Existing local data and pending photo files are not deleted. Re-login is required to resume cloud sync.
- APK/device verification remains pending.
"""
        progress.write_text(log, encoding="utf-8")
MAIN.write_text(source, encoding="utf-8")
print("Step215 camera-return auth warning guard applied.")
