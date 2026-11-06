from pathlib import Path
root = Path(__file__).resolve().parents[1]
sender = (root / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
pull = (root / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
checks = {
    'sender rechecks owner before each queued operation': 'for (operation in pending) {\n            // WorkManager' in sender and 'requireActiveOwner(ownerId)' in sender,
    'sender rechecks after claiming before reading payload': 'val claimed = dao.claimOutboxForSend(operation.operationId)' in sender and 'try {\n                requireActiveOwner(ownerId)' in sender,
    'sender rechecks immediately before Storage upload after local hashing': sender.index('val expectedDigest = PhotoIntegrity.sha256(photoBytes)') < sender.index('requireActiveOwner(ownerId)', sender.index('val expectedDigest = PhotoIntegrity.sha256(photoBytes)')) < sender.index('client.storage.from("part-images").upload'),
    'sender rechecks after upload before RPC': 'val payload = photoUpload?.payload ?: originalPayload\n                // Photo upload' in sender and 'requireActiveOwner(ownerId)\n                val rpcResponse' in sender,
    'sender rechecks after RPC before response acknowledgement': 'requireActiveOwner(ownerId)\n                val responseObject' in sender,
    'sender guard compares exact Supabase user ID': 'activeId == ownerId' in sender,
    'pull rechecks session after RPC response before parsing/applying page': (pull.index('if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()', pull.index('val response = client.postgrest.rpc')) < pull.index('val root = Json.parseToJsonElement(response.toString())', pull.index('val response = client.postgrest.rpc'))),
    'pull rechecks before applying every event': 'for (eventElement in events) {\n                    // Re-check' in pull and 'if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()' in pull,
    'pull retains initial owner check': 'if (currentUser != ownerId) return Result.success()' in pull,
    'worker remains owner-partitioned on DAO operations': 'dao.applyPulledPart(ownerId, part, deleted)' in pull and 'dao.applyPulledMovement(ownerId,' in pull,
}
for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'{sum(checks.values())}/{len(checks)} passed')
raise SystemExit(0 if all(checks.values()) else 1)
