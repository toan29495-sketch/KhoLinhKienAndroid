package vn.kholinhkien

/**
 * Age-based diagnostics for local weekly-backup journals.
 * Classification is deliberately non-destructive: long offline periods must not
 * abandon resumable uploads or erase local evidence automatically.
 */
internal object WeeklyBackupLifecycle {
    const val PREPARING_STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L
    const val UPLOAD_STALE_AFTER_MILLIS = 30L * 24L * 60L * 60L * 1000L

    enum class Classification {
        FRESH,
        STALE_PREPARING_RECOVERABLE,
        STALE_UPLOAD_RESUMABLE,
        VERIFIED_LOCAL_CLEANUP_ONLY,
        TERMINAL_RETAINED
    }

    /**
     * A new trigger may create a new identity only when no active journal exists.
     * PREPARING is reconstructed under its original ID; READY/UPLOADING must keep
     * their exact archive identity so a TUS retry never changes its fingerprint.
     */
    fun backupIdForAttempt(active: WeeklyBackupJournal?, proposedBackupId: String): String {
        if (active != null) {
            require(isCanonicalOwnerId(active.ownerId)) { "Owner journal backup không hợp lệ" }
        }
        require(runCatching { java.util.UUID.fromString(proposedBackupId).toString() == proposedBackupId }.getOrDefault(false)) {
            "backupId mới phải là UUID canonical"
        }
        if (active == null) return proposedBackupId
        require(active.state in setOf("PREPARING", "READY", "UPLOADING")) {
            "Journal không ở trạng thái có thể tiếp tục: ${active.state}"
        }
        require(runCatching { java.util.UUID.fromString(active.backupId).toString() == active.backupId }.getOrDefault(false)) {
            "backupId hiện tại không hợp lệ"
        }
        return active.backupId
    }

    fun classify(state: String, updatedAt: Long, nowMillis: Long): Classification {
        // A device clock moving backwards must never make a journal look stale.
        val age = (nowMillis - updatedAt).coerceAtLeast(0L)
        return when (state) {
            "PREPARING" -> if (age >= PREPARING_STALE_AFTER_MILLIS)
                Classification.STALE_PREPARING_RECOVERABLE else Classification.FRESH
            "READY", "UPLOADING" -> if (age >= UPLOAD_STALE_AFTER_MILLIS)
                Classification.STALE_UPLOAD_RESUMABLE else Classification.FRESH
            "VERIFIED" -> Classification.VERIFIED_LOCAL_CLEANUP_ONLY
            "FAILED", "ABANDONED" -> Classification.TERMINAL_RETAINED
            else -> throw IllegalArgumentException("Trạng thái journal backup không được hỗ trợ: $state")
        }
    }
}
