package vn.kholinhkien

import android.content.Context
import io.github.jan.supabase.auth.auth
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-wide gate serializes restore/import/resume/abort transitions. */
internal object RestoreOperationGate {
    val mutex = Mutex()
}

/** Serializes the complete cloud snapshot read + local apply across Coordinator and WorkManager. */
internal object RestoreSnapshotGate {
    val mutex = Mutex()
}

/** Owner-scoped lifecycle operations for durable ZIP staging. This class never mutates live inventory. */
object RestoreStagingLifecycle {
    data class ResumeInfo(
        val restoreId: String,
        val status: String,
        val stagedParts: Int,
        val expectedParts: Int,
        val stagedMovements: Int,
        val expectedMovements: Int,
        val photosReady: Boolean
    )

    suspend fun inspect(context: Context, dao: InventoryDao, ownerId: String): ResumeInfo? {
        requireActiveOwner(ownerId)
        val journal = dao.activeRestore(ownerId) ?: run {
            requireActiveOwner(ownerId)
            return null
        }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        requireActiveOwner(ownerId)
        val directory = safeStageDirectory(context, journal)
        val rowsValid = validateSourceRows(dao, journal)
        val photosReady = rowsValid && validatePhotoFiles(directory, dao, journal)
        requireActiveOwner(ownerId)
        return ResumeInfo(
            restoreId = journal.restoreId,
            status = journal.status,
            stagedParts = dao.countStagedRestoreRows(ownerId, journal.restoreId, "PART"),
            expectedParts = journal.partCount,
            stagedMovements = dao.countStagedRestoreRows(ownerId, journal.restoreId, "MOVEMENT"),
            expectedMovements = journal.movementCount,
            photosReady = photosReady
        )
    }

    /** A STAGED session is resumable by the next coordinator phase; incomplete ZIP staging must be re-imported. */
    suspend fun canResumeStaged(context: Context, dao: InventoryDao, ownerId: String, restoreId: String): Boolean {
        if (!isActiveOwner(ownerId)) return false
        val journal = dao.restoreJournal(ownerId, restoreId) ?: return false
        if (!isActiveOwner(ownerId) || journal.ownerId != ownerId || journal.status != "STAGED") return false
        val directory = runCatching { safeStageDirectory(context, journal) }.getOrNull() ?: return false
        val valid = validateSourceRows(dao, journal) && validatePhotoFiles(directory, dao, journal)
        return valid && isActiveOwner(ownerId)
    }

    /** Validate the actual durable rows, not just their aggregate counts. */
    internal suspend fun validateSourceRows(dao: InventoryDao, journal: RestoreJournal): Boolean {
        if (journal.partCount < 0 || journal.movementCount < 0) return false
        return runCatching {
            val parts = dao.stagedRestoreRows(journal.ownerId, journal.restoreId, "PART")
            val movements = dao.stagedRestoreRows(journal.ownerId, journal.restoreId, "MOVEMENT")
            parts.all { it.ownerId == journal.ownerId && it.restoreId == journal.restoreId && it.entityType == "PART" } &&
                movements.all { it.ownerId == journal.ownerId && it.restoreId == journal.restoreId && it.entityType == "MOVEMENT" } &&
                validateChunkedRows(parts, journal.partCount) &&
                validateChunkedRows(movements, journal.movementCount)
        }.getOrDefault(false)
    }

    private fun validateChunkedRows(rows: List<RestoreStagedRow>, expected: Int): Boolean {
        if (rows.size != expected) return false
        if (rows.isEmpty()) return true
        val grouped = rows.groupBy { it.chunkIndex }.toSortedMap()
        if (grouped.keys.toList() != (0 until grouped.size).toList()) return false
        var total = 0
        for ((_, chunk) in grouped) {
            if (chunk.isEmpty() || chunk.size > 200) return false
            if (chunk.map { it.rowIndex } != chunk.indices.toList()) return false
            if (chunk.any { it.payloadJson.isBlank() }) return false
            total += chunk.size
        }
        return total == expected
    }

    private suspend fun validatePhotoFiles(directory: File, dao: InventoryDao, journal: RestoreJournal): Boolean {
        return runCatching {
            val canonicalDirectory = directory.canonicalFile
            if (!canonicalDirectory.isDirectory) return false
            val rows = dao.stagedRestoreRows(journal.ownerId, journal.restoreId, "PART")
            val referenced = rows.mapNotNull { row ->
                val entry = org.json.JSONObject(row.payloadJson).optString("photoEntry", "").trim()
                entry.takeIf { it.isNotEmpty() }
            }.toSet()
            val photosRoot = File(canonicalDirectory, "photos").canonicalFile
            if (!photosRoot.isDirectory) return false
            referenced.all { entry ->
                if (!entry.startsWith("photos/") || entry.contains('\\') ||
                    entry.split('/').any { it.isBlank() || it == "." || it == ".." }) return@all false
                val file = File(canonicalDirectory, entry).canonicalFile
                file.path.startsWith(photosRoot.path + File.separator) && file.isFile && file.length() > 0L &&
                    file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
            }
        }.getOrDefault(false)
    }


    /**
     * Post-commit cleanup. Keeps the COMPLETE journal as an audit/recovery marker,
     * removes only local staged rows/files, and never deletes cloud Storage objects,
     * live inventory, movement history, outbox, or sync cursor.
     * Safe to call repeatedly after a process death.
     */
    suspend fun cleanupCompleted(
        context: Context,
        dao: InventoryDao,
        ownerId: String,
        restoreId: String
    ): Boolean {
        requireActiveOwner(ownerId)
        require(restoreId.isNotBlank()) { "Thiếu restoreId" }
        val journal = dao.restoreJournal(ownerId, restoreId) ?: return false
        require(journal.ownerId == ownerId) { "Không thể dọn phiên của tài khoản khác" }
        requireActiveOwner(ownerId)
        require(journal.status == "COMPLETE") { "Chỉ được dọn phiên đã hoàn tất chuyển đổi Room" }
        require(dao.getSyncCursor(ownerId) == journal.snapshotCursor) {
            "Cursor hiện tại không khớp snapshot đã áp dụng; giữ nguyên dữ liệu staging"
        }
        requireActiveOwner(ownerId)
        val directory = safeStageDirectory(context, journal)

        // The COMPLETE journal is retained. If the process stops after the DB cleanup,
        // the journal still provides the canonical directory for a subsequent cleanup.
        requireActiveOwner(ownerId)
        dao.clearCompletedRestoreStagedRows(ownerId, restoreId)
        requireActiveOwner(ownerId)
        if (directory.exists() && !directory.deleteRecursively()) {
            return false
        }
        return true
    }

    /** Abort is intentionally unavailable after cloud commit has started. */
    suspend fun abort(context: Context, dao: InventoryDao, ownerId: String, restoreId: String) =
        RestoreOperationGate.mutex.withLock {
            requireActiveOwner(ownerId)
            require(restoreId.isNotBlank()) { "Thiếu thông tin phiên khôi phục" }
            val journal = dao.restoreJournal(ownerId, restoreId) ?: return@withLock
            require(journal.ownerId == ownerId) { "Không thể hủy phiên khôi phục của tài khoản khác" }
            requireActiveOwner(ownerId)
            require(journal.status in setOf("STAGING", "STAGED", "FAILED")) {
                "Không thể hủy phiên ở trạng thái ${journal.status}; máy chủ có thể đã bắt đầu commit"
            }
            val directory = safeStageDirectory(context, journal)
            requireActiveOwner(ownerId)
            AppDb.get(context).withTransaction {
                // The shared gate excludes import/resume while this journal and its rows are removed.
                // The conditional DELETE remains a second line of defense against a state transition.
                val deleted = dao.deleteAbortableRestoreJournal(ownerId, restoreId)
                check(deleted == 1) { "Trạng thái phiên đã thay đổi; không thể hủy an toàn" }
                dao.clearRestoreStagedRows(ownerId, restoreId)
            }
            requireActiveOwner(ownerId)
            if (directory.exists()) {
                check(directory.deleteRecursively()) { "Đã hủy dữ liệu staging nhưng chưa xóa được tệp tạm" }
            }
        }

    private fun isActiveOwner(ownerId: String): Boolean = ownerId.isNotBlank() &&
        SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId

    private fun requireActiveOwner(ownerId: String) {
        require(isActiveOwner(ownerId)) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng thao tác staging"
        }
    }

    private fun safeStageDirectory(context: Context, journal: RestoreJournal): File {
        val root = File(context.filesDir, "restore_staging").canonicalFile
        val directory = File(journal.stagedDirectory).canonicalFile
        require(directory.parentFile?.canonicalFile == root && directory.name == journal.restoreId) {
            "Đường dẫn staging trong journal không an toàn"
        }
        return directory
    }
}
