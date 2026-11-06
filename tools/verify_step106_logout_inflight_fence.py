from pathlib import Path
root = Path(__file__).resolve().parents[1]
sender = (root/'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
pull = (root/'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
work = (root/'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text()
main = (root/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
checks = {
    'sender explicitly rethrows coroutine cancellation': 'catch (cancelled: CancellationException)' in sender and 'throw cancelled' in sender,
    'sender does not mark cancelled in-flight operation FAILED': sender.index('catch (cancelled: CancellationException)') < sender.index('catch (error: Exception)'),
    'sender documents lease-based idempotent recovery after cancellation': 'lease recovery will retry idempotently' in sender,
    'pull worker propagates cancellation instead of converting it to retry': 'catch (cancelled: CancellationException)' in pull and 'throw cancelled' in pull,
    'outbox worker propagates cancellation': 'catch (cancelled: CancellationException)' in work and 'throw cancelled' in work,
    'scheduler cancels owner push one-shot and periodic work': 'cancelUniqueWork("inventory-sync-once-$ownerId")' in work and 'cancelUniqueWork("inventory-sync-periodic-$ownerId")' in work,
    'scheduler cancels owner pull one-shot and periodic work': 'cancelUniqueWork("inventory-pull-once-$ownerId")' in work and 'cancelUniqueWork("inventory-pull-periodic-$ownerId")' in work,
    'scheduler also cancels Admin pull work names': 'cancelUniqueWork("inventory-pull-once-admin-$ownerId")' in work and 'cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")' in work,
    'logout cancels owner workers before ending Supabase session': main.index('SyncWorkScheduler.cancelOwner(context, ownerId)') < main.index('authRepository.signOut()', main.index('val signOut:')),
    'logout verifies that shared auth session is actually cleared': 'check(!authRepository.isLoggedIn())' in main,
    'logout does not silently clear UI while old session remains': 'Phiên hiện tại vẫn được giữ' in main and 'currentProfile = null' in main,
    'failed logout restores owner and role-appropriate Admin work for still-authenticated owner': 'SyncWorkScheduler.schedule(context, ownerId, currentProfile?.role?.equals("admin", ignoreCase = true) == true)' in main,
    'logout clears the signed-in UI without requiring a disk photo-cache purge': 'clearAccountPhotoCache' not in main and 'clearPartPhotoCache' not in main and 'check(!authRepository.isLoggedIn())' in main and 'currentProfile = null' in main,
}
failed=[]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL')+' '+name)
    if not ok: failed.append(name)
print(f'{len(checks)-len(failed)}/{len(checks)} PASS')
raise SystemExit(bool(failed))
