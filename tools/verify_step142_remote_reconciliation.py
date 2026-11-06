from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = (ROOT / "supabase/REMOTE_MIGRATION_MANIFEST.md").read_text(encoding="utf-8")
REVIEW = (ROOT / "STEP142_REVIEW.md").read_text(encoding="utf-8")
ACTIVE = (ROOT / "supabase/functions/admin-users/index.ts").read_text(encoding="utf-8")
CANDIDATE = (ROOT / "supabase/functions/admin-users-v4-candidate/index.ts").read_text(encoding="utf-8")

checks = {
    "remote history lists nine Step139 migrations": all(
        f"step139_0{i}_" in MANIFEST for i in range(1, 10)
    ),
    "manifest distinguishes deployed history from unreconciled SQL":
        "exact SQL source reconciliation remains pending" in MANIFEST,
    "manifest forbids blind pending migration replay":
        "Do not replay files" in MANIFEST and "Do not use blanket `db push`" in MANIFEST,
    "manifest records current-state fingerprint limitation":
        "not a schema dump" in MANIFEST and "not a replacement" in MANIFEST,
    "all twelve remote function fingerprints recorded": all(x in MANIFEST for x in [
        "7d9e7271add6214abd3202d7f89a0fb6", "3ba8c07306bad56b5c8648fc379f3a95",
        "fb583d3f8d272965fce8c949c7dbeab5", "d6760158a3d83d57ba5e80dc9ce459d9",
        "5b16b595149b9def2fcdb5822202fc0a", "e21dff501ee257a2d54cb6d8aecd263a",
        "0927b84a1a88a600ffabd0bbd1764ff1", "367870b754a48cfc5187918e4b1723d5",
        "47c5c4488bd02c5760af9b190e7c5bef", "34d3f71a062f7ec98561f04022f19c2e",
        "d562368841884e1bf4465518d51cb26b", "87340355bfbf3608e933db79ae326af6",
    ]),
    "review explicitly says read-only and no live changes":
        "No live SQL, migration, account, Storage, Edge Function, or business-data changes were made." in REVIEW,
    "review distinguishes candidate from deployed v3":
        "Candidate v4 is source-only and NOT deployed" in REVIEW,
    "active v3 source has no app_admins check": 'from("app_admins")' not in ACTIVE,
    "v4 candidate has app_admins membership check":
        '.from("app_admins")' in CANDIDATE and "if (!adminLink)" in CANDIDATE,
    "v4 candidate validates identity before profile/admin link":
        ACTIVE.index("auth.getUser") < ACTIVE.index('from("profiles")'),
    "v4 candidate retains explicit bearer token verification":
        'authorization.match(/^Bearer\\s+(.+)$/i)' in CANDIDATE and "auth.getUser(tokenMatch[1])" in CANDIDATE,
    "no migration replay or APK claim":
        "No migration replay" in REVIEW and "No migration replay" in REVIEW,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + name)
print(f"STEP142_RESULT={len(checks)-len(failed)}/{len(checks)}")
if failed:
    raise SystemExit(1)
