package vn.kholinhkien

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import org.json.JSONObject

@Entity(
    tableName = "parts",
    indices = [Index(value = ["ownerId", "code"], unique = true), Index(value = ["syncId"], unique = true)]
)
data class Part(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val quantity: Int = 0,
    val minQuantity: Int = 0,
    val unit: String = "cái",
    val photoPath: String = "",
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    // SINGLE = một món/mã; BULK = nhiều món dùng chung một mã QR
    @ColumnInfo(defaultValue = "'SINGLE'") val trackingMode: String = "SINGLE",
    // Mỗi tài khoản sở hữu kho riêng. Chuỗi rỗng chỉ tồn tại trong dữ liệu cũ
    // trước khi được gán cho tài khoản đăng nhập đầu tiên sau migration.
    @ColumnInfo(defaultValue = "''") val ownerId: String = "",
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val serverVersion: Long = 0L
)

@Entity(tableName = "movements", indices = [Index(value = ["ownerId", "time"]), Index(value = ["syncId"], unique = true)])
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val partName: String,
    val type: String,
    val amount: Int,
    val beforeQty: Int,
    val afterQty: Int,
    val reason: String = "",
    val machineOrStore: String = "",
    val time: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val ownerId: String = "",
    @ColumnInfo(defaultValue = "''") val ownerName: String = "",
    @ColumnInfo(defaultValue = "''") val syncId: String = UUID.randomUUID().toString()
)


@Entity(tableName = "sync_outbox", indices = [Index(value = ["ownerId", "status", "createdAt"])])
data class SyncOutbox(
    @PrimaryKey val operationId: String = UUID.randomUUID().toString(),
    val ownerId: String,
    val operationType: String,
    val entityId: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val lastError: String = "",
    @ColumnInfo(defaultValue = "0") val claimStartedAt: Long = 0L
)

@Entity(tableName = "sync_cursors")
data class SyncCursor(
    @PrimaryKey val ownerId: String,
    val lastEventId: Long = 0
)

/** Durable owner-scoped version fence for remotely deleted parts. A physical
 * Part row may be removed, but its latest tombstone version must survive replay. */
@Entity(tableName = "part_tombstones", primaryKeys = ["ownerId", "syncId"])
data class PartTombstone(
    val ownerId: String,
    val syncId: String,
    val serverVersion: Long,
    val deletedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_conflict_decisions", indices = [Index(value = ["ownerId", "status"])])
data class SyncConflictDecision(
    @PrimaryKey val blockedOperationId: String,
    val ownerId: String,
    val decisionKind: String,
    val status: String = "PREPARED",
    val planJson: String = "{}",
    val dependencyCount: Int = 0,
    @ColumnInfo(defaultValue = "''") val replacementOperationId: String = "",
    @ColumnInfo(defaultValue = "0") val replacementServerVersion: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class BlockedResolutionPreflight(
    val blocker: SyncOutbox,
    val dependentOperations: List<SyncOutbox>,
    val sameEntityOnly: Boolean,
    val containsAttemptedOperations: Boolean
)

/** Immutable owner-scoped view captured from one Room read transaction. */
data class InventoryBackupSnapshot(
    val ownerId: String,
    val syncCursor: Long,
    val parts: List<Part>,
    val movements: List<Movement>
)

@Entity(tableName = "weekly_backup_journal", indices = [Index(value = ["ownerId", "state"])])
data class WeeklyBackupJournal(
    @PrimaryKey val backupId: String,
    val ownerId: String,
    val state: String,
    val archivePath: String,
    val createdAt: Long,
    val snapshotCursor: Long = -1L,
    val partCount: Int = -1,
    val movementCount: Int = -1,
    val archiveSizeBytes: Long = -1L,
    val sha256: String = "",
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "restore_journal", indices = [Index(value = ["ownerId", "status"])])
data class RestoreJournal(
    @PrimaryKey val restoreId: String,
    val ownerId: String,
    val manifestSha256: String,
    val status: String,
    val stagedDirectory: String,
    val partCount: Int,
    val movementCount: Int,
    val snapshotCursor: Long = 0L,
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'LOCAL_ZIP'") val sourceType: String = "LOCAL_ZIP",
    @ColumnInfo(defaultValue = "-1") val partChunkCount: Int = -1,
    @ColumnInfo(defaultValue = "-1") val movementChunkCount: Int = -1
)

/** Durable, owner-scoped rows staged from a ZIP before cloud commit. These rows
 * never replace live inventory by themselves; the restore coordinator owns the
 * transition from STAGING to cloud commit and local reconciliation. */
@Entity(
    tableName = "restore_staged_rows",
    primaryKeys = ["restoreId", "entityType", "chunkIndex", "rowIndex"],
    indices = [Index(value = ["ownerId", "restoreId", "entityType", "chunkIndex"])]
)
data class RestoreStagedRow(
    val restoreId: String,
    val ownerId: String,
    val entityType: String,
    val chunkIndex: Int,
    val rowIndex: Int,
    val payloadJson: String
)

@Dao
interface InventoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(operation: SyncOutbox)

    // FAILED được lấy lại để retry; rowid giữ đúng thứ tự enqueue khi createdAt trùng mili-giây.
    // IN_FLIGHT được phục hồi riêng khi worker khởi động lại sau khi app bị dừng đột ngột.
    @Query("SELECT * FROM sync_outbox o WHERE o.ownerId = :ownerId AND o.status IN ('PENDING', 'FAILED') AND NOT EXISTS (SELECT 1 FROM sync_outbox b WHERE b.ownerId = :ownerId AND b.status = 'BLOCKED') AND NOT EXISTS (SELECT 1 FROM sync_outbox f WHERE f.ownerId = :ownerId AND f.status = 'IN_FLIGHT') ORDER BY createdAt ASC, rowid ASC")
    suspend fun pendingOutbox(ownerId: String): List<SyncOutbox>

    @Query("UPDATE sync_outbox SET status = 'IN_FLIGHT', attemptCount = attemptCount + 1, lastError = '', claimStartedAt = :now WHERE operationId = :operationId AND status IN ('PENDING', 'FAILED')")
    suspend fun markOutboxInFlight(operationId: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE sync_outbox SET status = 'SYNCED', lastError = '', claimStartedAt = 0 WHERE operationId = :operationId AND status = 'IN_FLIGHT'")
    suspend fun markOutboxSynced(operationId: String): Int


    @Query("UPDATE parts SET photoPath = :remotePath WHERE ownerId = :ownerId AND syncId = :entityId AND (photoPath = :localPath OR photoPath = :remotePath)")
    suspend fun replaceLocalPhotoWithCloudPath(ownerId: String, entityId: String, localPath: String, remotePath: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId = :ownerId AND operationId <> :operationId AND status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED') AND instr(payloadJson, :photoPath) > 0)")
    suspend fun hasPendingPhotoReference(ownerId: String, photoPath: String, operationId: String): Boolean

    // A legacy absolute photo path has no owner directory. It is safe to read only
    // when the local database proves that the exact path is referenced by this owner
    // and is not referenced by any other owner (parts OR immutable outbox payloads).
    // instr() is intentionally conservative: a substring collision may reject a file,
    // but must never grant cross-account access.
    @Query("""SELECT (
        (EXISTS(SELECT 1 FROM parts WHERE ownerId = :ownerId AND photoPath = :photoPath)
         OR EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId = :ownerId AND instr(payloadJson, :photoPath) > 0))
        AND NOT EXISTS(SELECT 1 FROM parts WHERE ownerId <> :ownerId AND photoPath = :photoPath)
        AND NOT EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId <> :ownerId AND instr(payloadJson, :photoPath) > 0)
    )""")
    suspend fun isLocalPhotoReferencedOnlyByOwner(ownerId: String, photoPath: String): Boolean

    @Transaction
    suspend fun completePhotoOutbox(operationId: String, ownerId: String, entityId: String, localPath: String, remotePath: String, serverVersion: Long): Boolean {
        require(serverVersion > 0L) { "server_version không hợp lệ" }
        val operation = outboxById(operationId) ?: return false
        check(operation.ownerId == ownerId && operation.entityId == entityId &&
            operation.operationType == "PART_UPSERT" && operation.status == "IN_FLIGHT") {
            "Giao dịch ảnh không còn đúng owner/entity/trạng thái đang gửi"
        }

        // The user may have changed the photo again (or deleted the Part) while
        // this older immutable Outbox payload was in flight. Never overwrite the
        // newer local choice; the older cloud operation must still be acknowledged
        // so the FIFO queue can advance to the newer edit/delete.
        val currentPart = partBySyncId(ownerId, entityId)
        if (currentPart != null) {
            replaceLocalPhotoWithCloudPath(ownerId, entityId, localPath, remotePath)
            check(updatePartServerVersion(ownerId, entityId, serverVersion) == 1) {
                "Không thể ghi nhận phiên bản máy chủ của ảnh đã đồng bộ"
            }
        } else {
            val laterDelete = queuedPartOperations(ownerId, entityId).any { it.operationType == "PART_DELETE" }
            check(laterDelete) {
                "Linh kiện đã biến mất nhưng không có giao dịch xóa tiếp nối để đối soát ảnh"
            }
        }

        // Rebase only later queued operations. A missing Part is valid only in
        // the delete-after-upsert sequence checked above.
        var nextExpectedVersion = serverVersion
        queuedPartOperations(ownerId, entityId).forEach { row ->
            val json = JSONObject(row.payloadJson)
            json.put("expected_server_version", nextExpectedVersion)
            check(updateQueuedPayload(row.operationId, json.toString()) == 1) {
                "Không thể cập nhật phiên bản cho giao dịch đang chờ"
            }
            nextExpectedVersion = Math.addExact(nextExpectedVersion, 1L)
        }
        return markOutboxSynced(operationId) == 1
    }

    @Transaction
    suspend fun completeOutboxWithServerVersion(operationId: String, ownerId: String, entityId: String, serverVersion: Long): Boolean {
        require(serverVersion > 0L) { "server_version không hợp lệ" }
        // The server may already have committed this immutable operation when the
        // process died or the response was lost. Local acknowledgement must be one
        // atomic Room transaction: validate the exact claimed row, persist the
        // server version/rebase successors, and mark that same row SYNCED together.
        // If any step fails, throwing rolls the entire transaction back so retrying
        // the same operationId/payload can safely receive the server's cached result.
        val operation = outboxById(operationId) ?: return false
        check(operation.ownerId == ownerId && operation.entityId == entityId &&
            operation.operationType in setOf("PART_UPSERT", "STOCK_CHANGE") &&
            operation.status == "IN_FLIGHT") {
            "Giao dịch đồng bộ không còn đúng owner/entity/loại/trạng thái đang gửi"
        }
        recordServerVersionAndRebase(ownerId, entityId, serverVersion)
        check(markOutboxSynced(operationId) == 1) {
            "Không thể xác nhận giao dịch đã đồng bộ; rollback cập nhật phiên bản cục bộ"
        }
        return true
    }

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND entityId = :entityId AND operationId <> :operationId AND operationType IN ('PART_UPSERT','PART_DELETE','STOCK_CHANGE') AND status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED')")
    suspend fun countOtherUnresolvedPartOperations(ownerId: String, entityId: String, operationId: String): Int

    @Transaction
    suspend fun completeDeleteOutbox(operationId: String, ownerId: String, entityId: String): Boolean {
        val row = outboxById(operationId) ?: return false
        check(row.ownerId == ownerId && row.entityId == entityId && row.operationType == "PART_DELETE" && row.status == "IN_FLIGHT") {
            "Outbox xóa không còn đúng owner/entity/trạng thái đang gửi"
        }
        // A successful remote tombstone has no local Part row to version-update:
        // deletePartByCode removed it atomically when the delete was queued.
        check(partBySyncId(ownerId, entityId) == null) {
            "Linh kiện đã xuất hiện lại cục bộ; không tự xác nhận giao dịch xóa"
        }
        check(countOtherUnresolvedPartOperations(ownerId, entityId, operationId) == 0) {
            "Còn giao dịch khác chưa xử lý cho linh kiện đã xóa; cần đối soát"
        }
        return markOutboxSynced(operationId) == 1
    }

    @Query("UPDATE sync_outbox SET status = 'FAILED', lastError = :error, claimStartedAt = 0 WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'IN_FLIGHT'")
    suspend fun markOutboxFailed(ownerId: String, operationId: String, error: String): Int

    @Query("UPDATE sync_outbox SET status = 'FAILED', lastError = 'Lease hết hạn; giữ nguyên operationId/payload để đối soát idempotency', claimStartedAt = 0 WHERE ownerId = :ownerId AND status = 'IN_FLIGHT' AND claimStartedAt > 0 AND claimStartedAt < :cutoff")
    suspend fun recoverExpiredClaims(ownerId: String, cutoff: Long): Int

    @Query("UPDATE sync_outbox SET status = 'BLOCKED', lastError = :error, claimStartedAt = 0 WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'IN_FLIGHT'")
    suspend fun markOutboxBlocked(ownerId: String, operationId: String, error: String): Int

    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND (status = 'BLOCKED' OR (status = 'IN_FLIGHT' AND claimStartedAt = 0)) ORDER BY createdAt, rowid")
    fun syncAttentionOutbox(ownerId: String): Flow<List<SyncOutbox>>

    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND (status = 'BLOCKED' OR (status = 'IN_FLIGHT' AND claimStartedAt = 0)) ORDER BY createdAt, rowid LIMIT 1")
    suspend fun firstBlockedOutbox(ownerId: String): SyncOutbox?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED') AND (createdAt > (SELECT createdAt FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND (status = 'BLOCKED' OR (status = 'IN_FLIGHT' AND claimStartedAt = 0)) LIMIT 1) OR (createdAt = (SELECT createdAt FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND (status = 'BLOCKED' OR (status = 'IN_FLIGHT' AND claimStartedAt = 0)) LIMIT 1) AND rowid > (SELECT rowid FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND (status = 'BLOCKED' OR (status = 'IN_FLIGHT' AND claimStartedAt = 0)) LIMIT 1)))")
    suspend fun countQueuedBehindBlocked(ownerId: String, operationId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId = :ownerId AND entityId = :entityId AND status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED'))")
    suspend fun hasUnsentOperation(ownerId: String, entityId: String): Boolean

    @Query("SELECT lastEventId FROM sync_cursors WHERE ownerId = :ownerId LIMIT 1")
    suspend fun getSyncCursor(ownerId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncCursor(cursor: SyncCursor)

    // Pull workers can overlap (one-time vs periodic work, or separate feed modes).
    // A slower worker must never replace a cursor already advanced by a faster one.
    // UPDATE + INSERT IGNORE is used instead of SQLite UPSERT for older Android SQLite.
    @Query("UPDATE sync_cursors SET lastEventId = CASE WHEN lastEventId < :eventId THEN :eventId ELSE lastEventId END WHERE ownerId = :cursorOwnerId")
    suspend fun updateSyncCursorMaximum(cursorOwnerId: String, eventId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncCursorIfAbsent(cursor: SyncCursor): Long

    @Transaction
    suspend fun savePulledCursorIfUnlocked(ownerId: String, cursor: SyncCursor): Boolean {
        if (activeRestore(ownerId) != null) return false
        require(ownerId.isNotBlank() && cursor.lastEventId >= 0L) { "Cursor đồng bộ không hợp lệ" }
        require(cursor.ownerId == ownerId || cursor.ownerId == "admin-global:$ownerId") {
            "Cursor đồng bộ không thuộc tài khoản hiện tại"
        }
        updateSyncCursorMaximum(cursor.ownerId, cursor.lastEventId)
        insertSyncCursorIfAbsent(cursor)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRestoreJournal(journal: RestoreJournal)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRestoreJournalIfAbsent(journal: RestoreJournal)

    /**
     * Atomically reserve a local ZIP restore. The process Mutex is only an in-process
     * optimization; this Room transaction is the durable single-active-restore fence.
     * It prevents two app processes/work entry points from both passing a read-then-write
     * activeRestore() check and staging different archives for the same owner.
     */
    @Transaction
    suspend fun beginLocalRestoreIfSafe(journal: RestoreJournal): Boolean {
        require(journal.sourceType == "LOCAL_ZIP" && journal.status == "STAGING")
        require(journal.ownerId.isNotBlank() && journal.restoreId.isNotBlank())
        if (restoreJournal(journal.ownerId, journal.restoreId) != null) return false
        if (activeRestore(journal.ownerId) != null) return false
        insertRestoreJournalIfAbsent(journal)
        return true
    }

    /** Atomically reserve a remote snapshot only when no local outbox work or other restore exists. */
    @Transaction
    suspend fun beginRemoteRestoreIfSafe(journal: RestoreJournal): Boolean {
        require(journal.sourceType == "REMOTE_FEED" && journal.status == "REMOTE_COMMIT_CONFIRMED")
        require(journal.ownerId.isNotBlank() && journal.restoreId.isNotBlank())
        if (restoreJournal(journal.ownerId, journal.restoreId) != null) return false
        if (activeRestore(journal.ownerId) != null) return false
        if (countUnsentOutbox(journal.ownerId) != 0) return false
        saveRestoreJournal(journal)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRestoreStagedRows(rows: List<RestoreStagedRow>)

    @Query("SELECT * FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId AND entityType = :entityType ORDER BY chunkIndex, rowIndex")
    suspend fun stagedRestoreRows(ownerId: String, restoreId: String, entityType: String): List<RestoreStagedRow>

    @Query("SELECT * FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId AND entityType = :entityType AND chunkIndex = :chunkIndex ORDER BY rowIndex")
    suspend fun stagedRestoreChunk(ownerId: String, restoreId: String, entityType: String, chunkIndex: Int): List<RestoreStagedRow>

    @Query("SELECT DISTINCT chunkIndex FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId AND entityType = :entityType ORDER BY chunkIndex")
    suspend fun stagedRestoreChunkIndexes(ownerId: String, restoreId: String, entityType: String): List<Int>

    @Query("SELECT COUNT(*) FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId AND entityType = :entityType")
    suspend fun countStagedRestoreRows(ownerId: String, restoreId: String, entityType: String): Int

    @Query("DELETE FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId")
    suspend fun clearRestoreStagedRows(ownerId: String, restoreId: String)

    /** Cleanup is permitted only after the atomic Room apply marked this exact journal COMPLETE. */
    @Query("DELETE FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId AND EXISTS (SELECT 1 FROM restore_journal WHERE restoreId = :restoreId AND ownerId = :ownerId AND status = 'COMPLETE')")
    suspend fun clearCompletedRestoreStagedRows(ownerId: String, restoreId: String): Int

    @Query("DELETE FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId AND entityType = :entityType")
    suspend fun clearRestoreStagedRowsByType(ownerId: String, restoreId: String, entityType: String)

    @Query("SELECT * FROM restore_journal WHERE ownerId = :ownerId AND status NOT IN ('COMPLETE', 'ABORTED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun activeRestore(ownerId: String): RestoreJournal?

    /** Every restore journal read is scoped to the authenticated local owner. */
    @Query("SELECT * FROM restore_journal WHERE ownerId = :ownerId AND restoreId = :restoreId LIMIT 1")
    suspend fun restoreJournal(ownerId: String, restoreId: String): RestoreJournal?

    @Query("DELETE FROM restore_journal WHERE ownerId = :ownerId AND restoreId = :restoreId AND status IN ('STAGING', 'STAGED', 'FAILED')")
    suspend fun deleteAbortableRestoreJournal(ownerId: String, restoreId: String): Int

    @Query("SELECT * FROM restore_journal WHERE ownerId = :ownerId ORDER BY updatedAt DESC")
    suspend fun restoreJournals(ownerId: String): List<RestoreJournal>

    /** Live owner-scoped journal stream keeps UI locks/status accurate after WorkManager recovery. */
    @Query("SELECT * FROM restore_journal WHERE ownerId = :ownerId ORDER BY updatedAt DESC")
    fun observeRestoreJournals(ownerId: String): Flow<List<RestoreJournal>>

    @Query("SELECT * FROM weekly_backup_journal WHERE ownerId = :ownerId AND backupId = :backupId LIMIT 1")
    suspend fun weeklyBackup(ownerId: String, backupId: String): WeeklyBackupJournal?

    /** Recovery reads only the currently authenticated owner's backup journals. */
    @Query("SELECT * FROM weekly_backup_journal WHERE ownerId = :ownerId ORDER BY createdAt DESC")
    suspend fun weeklyBackupsForOwner(ownerId: String): List<WeeklyBackupJournal>

    @Query("SELECT * FROM weekly_backup_journal WHERE ownerId = :ownerId AND state IN ('PREPARING','READY','UPLOADING') ORDER BY createdAt DESC, backupId ASC LIMIT 1")
    suspend fun activeWeeklyBackup(ownerId: String): WeeklyBackupJournal?

    /** Insert a durable identity before creating bytes; retries must reuse this row and path. */
    @Transaction
    suspend fun beginWeeklyBackup(journal: WeeklyBackupJournal): WeeklyBackupJournal {
        require(journal.ownerId.isNotBlank() && journal.backupId.isNotBlank())
        require(journal.state == "PREPARING")
        val existing = weeklyBackup(journal.ownerId, journal.backupId)
        if (existing != null) {
            check(existing.archivePath == journal.archivePath && existing.createdAt == journal.createdAt) {
                "backupId đã được dùng với archive/path khác"
            }
            return existing
        }
        check(activeWeeklyBackup(journal.ownerId) == null) {
            "Tài khoản đang có một bản sao lưu tuần chưa hoàn tất"
        }
        insertWeeklyBackup(journal)
        return journal
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWeeklyBackup(journal: WeeklyBackupJournal)

    @Query("UPDATE weekly_backup_journal SET state='READY', snapshotCursor=:cursor, partCount=:parts, movementCount=:movements, archiveSizeBytes=:sizeBytes, sha256=:sha256, lastError='', updatedAt=:now WHERE ownerId=:ownerId AND backupId=:backupId AND state='PREPARING'")
    suspend fun markWeeklyBackupReady(ownerId: String, backupId: String, cursor: Long, parts: Int, movements: Int, sizeBytes: Long, sha256: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE weekly_backup_journal SET state='UPLOADING', lastError='', updatedAt=:now WHERE ownerId=:ownerId AND backupId=:backupId AND state IN ('READY','UPLOADING')")
    suspend fun markWeeklyBackupUploading(ownerId: String, backupId: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE weekly_backup_journal SET state='VERIFIED', lastError='', updatedAt=:now WHERE ownerId=:ownerId AND backupId=:backupId AND state='UPLOADING'")
    suspend fun markWeeklyBackupVerified(ownerId: String, backupId: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE weekly_backup_journal SET state='FAILED', lastError=:error, updatedAt=:now WHERE ownerId=:ownerId AND backupId=:backupId AND state IN ('PREPARING','READY','UPLOADING')")
    suspend fun markWeeklyBackupFailed(ownerId: String, backupId: String, error: String, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status IN ('PENDING', 'FAILED', 'IN_FLIGHT', 'BLOCKED')")
    suspend fun countUnsentOutbox(ownerId: String): Int

    @Query("SELECT * FROM parts WHERE ownerId = :ownerId ORDER BY name COLLATE NOCASE, syncId")
    suspend fun backupPartsForOwner(ownerId: String): List<Part>

    @Query("SELECT * FROM movements WHERE ownerId = :ownerId ORDER BY time, id")
    suspend fun backupMovementsForOwner(ownerId: String): List<Movement>

    /**
     * Captures a consistent owner-only backup view. The Room transaction ensures
     * parts, movements, cursor and outbox/restore fences are read from one DB snapshot.
     * Null means the account is not in a safe, fully-synchronized state to back up.
     */
    @Transaction
    suspend fun captureAutomaticBackupSnapshot(ownerId: String): InventoryBackupSnapshot? {
        require(ownerId.isNotBlank()) { "Thiếu tài khoản sở hữu kho" }
        if (activeRestore(ownerId) != null) return null
        if (countUnsentOutbox(ownerId) != 0) return null
        val parts = backupPartsForOwner(ownerId)
        val movements = backupMovementsForOwner(ownerId)
        val cursor = getSyncCursor(ownerId) ?: 0L
        // Defensive re-check documents and enforces the precondition even if this
        // method is later refactored; Room transaction keeps the reads coherent.
        if (activeRestore(ownerId) != null || countUnsentOutbox(ownerId) != 0) return null
        return InventoryBackupSnapshot(ownerId, cursor, parts, movements)
    }

    @Query("DELETE FROM sync_outbox WHERE ownerId = :ownerId")
    suspend fun clearOutbox(ownerId: String)

    @Query("DELETE FROM sync_cursors WHERE ownerId = :ownerId")
    suspend fun clearSyncCursor(ownerId: String)

    @Query("DELETE FROM restore_journal WHERE ownerId = :ownerId AND status IN ('COMPLETE', 'ABORTED')")
    suspend fun pruneFinishedRestoreJournals(ownerId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPulledPart(part: Part)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPulledMovementRow(movement: Movement): Long

    /** Remove only foreign-owner movement rows cached by a former Admin device.
     * Server history is untouched; the Admin-global cursor is reset separately so
     * a later promotion can rehydrate the complete global feed.
     */
    @Query("DELETE FROM movements WHERE ownerId != :ownerId")
    suspend fun clearForeignMovementCache(ownerId: String): Int

    @Query("SELECT * FROM movements WHERE syncId = :syncId LIMIT 1")
    suspend fun movementBySyncId(syncId: String): Movement?

    @Query("UPDATE movements SET ownerName = :ownerName WHERE syncId = :syncId")
    suspend fun updatePulledMovementOwnerName(syncId: String, ownerName: String): Int

    @Transaction
    suspend fun applyPulledMovement(ownerId: String, movement: Movement): Boolean {
        // Keep restore lock, idempotency check and insert in one Room transaction.
        if (activeRestore(ownerId) != null) return false
        require(movement.ownerId == ownerId) { "Lịch sử đồng bộ không thuộc tài khoản hiện tại" }
        val existing = movementBySyncId(movement.syncId)
        if (existing != null) {
            // A replay is harmless only when the immutable event is byte-for-field equivalent.
            // A globally unique syncId with different data (or another owner) is corruption.
            // ownerName is profile metadata, not an immutable movement field. The
            // Admin feed enriches it while the owner feed may omit it; both feeds
            // must be safely replayable in either order.
            check(existing.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")) {
                "Xung đột syncId lịch sử: dữ liệu đã tồn tại không khớp sự kiện máy chủ"
            }
            if (movement.ownerName.isNotBlank() && existing.ownerName != movement.ownerName) {
                check(updatePulledMovementOwnerName(movement.syncId, movement.ownerName) == 1) {
                    "Không thể cập nhật tên chủ kho cho lịch sử đã đồng bộ"
                }
            }
            return true
        }
        val insertedId = insertPulledMovementRow(movement)
        if (insertedId == -1L) {
            // Defensive re-read for a uniqueness race; never silently advance the cursor.
            val raced = movementBySyncId(movement.syncId)
            check(raced != null && raced.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")) {
                "Không thể xác minh giao dịch lịch sử đã tồn tại"
            }
            if (movement.ownerName.isNotBlank() && raced.ownerName != movement.ownerName) {
                check(updatePulledMovementOwnerName(movement.syncId, movement.ownerName) == 1)
            }
        }
        return true
    }

    @Transaction
    suspend fun applyPulledAdminMovement(adminOwnerId: String, movement: Movement): Boolean {
        // The global feed is server-authorized for Admin only. Keep the local
        // restore fence tied to the signed-in Admin, while preserving the actual
        // KTV ownerId carried by the movement payload for system-wide history.
        if (activeRestore(adminOwnerId) != null) return false
        require(adminOwnerId.isNotBlank() && movement.ownerId.isNotBlank()) {
            "Admin global movement thiếu tài khoản"
        }
        require(UUID.fromString(adminOwnerId).toString() == adminOwnerId.lowercase()) {
            "Admin global movement có Admin ownerId không chuẩn"
        }
        val canonicalMovementOwner = UUID.fromString(movement.ownerId).toString()
        require(canonicalMovementOwner == movement.ownerId.lowercase()) {
            "Admin global movement có ownerId không chuẩn"
        }
        val existing = movementBySyncId(movement.syncId)
        if (existing != null) {
            // ownerName is enriched from the current profile in the global feed;
            // it may differ from the owner feed's empty or older display name.
            check(existing.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")) {
                "Xung đột syncId lịch sử toàn hệ thống: dữ liệu đã tồn tại không khớp sự kiện máy chủ"
            }
            if (movement.ownerName.isNotBlank() && existing.ownerName != movement.ownerName) {
                check(updatePulledMovementOwnerName(movement.syncId, movement.ownerName) == 1) {
                    "Không thể cập nhật tên chủ kho cho lịch sử toàn hệ thống"
                }
            }
            return true
        }
        val insertedId = insertPulledMovementRow(movement)
        if (insertedId == -1L) {
            val raced = movementBySyncId(movement.syncId)
            check(raced != null && raced.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")) {
                "Không thể xác minh lịch sử toàn hệ thống đã tồn tại"
            }
            if (movement.ownerName.isNotBlank() && raced.ownerName != movement.ownerName) {
                check(updatePulledMovementOwnerName(movement.syncId, movement.ownerName) == 1)
            }
        }
        return true
    }

    @Query("SELECT * FROM parts WHERE syncId = :syncId AND ownerId = :ownerId LIMIT 1")
    suspend fun partBySyncId(ownerId: String, syncId: String): Part?

    @Query("UPDATE parts SET serverVersion = :version WHERE ownerId = :ownerId AND syncId = :syncId")
    suspend fun updatePartServerVersion(ownerId: String, syncId: String, version: Long): Int

    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND entityId = :entityId AND operationType IN ('PART_UPSERT','PART_DELETE','STOCK_CHANGE') AND status IN ('PENDING','FAILED') ORDER BY createdAt, rowid")
    suspend fun queuedPartOperations(ownerId: String, entityId: String): List<SyncOutbox>

    @Query("UPDATE sync_outbox SET payloadJson = :payload WHERE operationId = :operationId AND status IN ('PENDING','FAILED')")
    suspend fun updateQueuedPayload(operationId: String, payload: String): Int

    @Query("SELECT * FROM sync_outbox WHERE operationId = :operationId LIMIT 1")
    suspend fun outboxById(operationId: String): SyncOutbox?

    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'BLOCKED' LIMIT 1")
    suspend fun blockedOperationById(ownerId: String, operationId: String): SyncOutbox?

    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED') AND (createdAt > (SELECT createdAt FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'BLOCKED' LIMIT 1) OR (createdAt = (SELECT createdAt FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'BLOCKED' LIMIT 1) AND rowid > (SELECT rowid FROM sync_outbox WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'BLOCKED' LIMIT 1))) ORDER BY createdAt, rowid LIMIT 50")
    suspend fun queuedBehindBlocked(ownerId: String, operationId: String): List<SyncOutbox>

    @Query("SELECT * FROM sync_conflict_decisions WHERE ownerId = :ownerId AND blockedOperationId = :operationId LIMIT 1")
    suspend fun conflictDecision(ownerId: String, operationId: String): SyncConflictDecision?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConflictDecision(decision: SyncConflictDecision)

    @Query("UPDATE sync_conflict_decisions SET status = 'READY_FOR_SERVER_RECHECK', updatedAt = :now WHERE ownerId = :ownerId AND blockedOperationId = :operationId AND status = 'PREPARED'")
    suspend fun markDecisionReady(ownerId: String, operationId: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE sync_outbox SET status = 'RESOLVED', lastError = 'Đã thay thế bằng giao dịch mới sau xác nhận xung đột', claimStartedAt = 0 WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'BLOCKED'")
    suspend fun markBlockedResolved(ownerId: String, operationId: String): Int

    @Query("UPDATE sync_conflict_decisions SET status = 'REPLACEMENT_QUEUED', replacementOperationId = :replacementId, replacementServerVersion = :serverVersion, updatedAt = :now WHERE ownerId = :ownerId AND blockedOperationId = :blockedId AND status = 'READY_FOR_SERVER_RECHECK' AND replacementOperationId = ''")
    suspend fun linkReplacement(ownerId: String, blockedId: String, replacementId: String, serverVersion: Long, now: Long = System.currentTimeMillis()): Int

    @Transaction
    suspend fun inspectBlockedResolution(ownerId: String, operationId: String): BlockedResolutionPreflight? {
        val blocker = blockedOperationById(ownerId, operationId) ?: return null
        val dependent = queuedBehindBlocked(ownerId, operationId)
        return BlockedResolutionPreflight(
            blocker = blocker,
            dependentOperations = dependent,
            sameEntityOnly = dependent.all { it.entityId == blocker.entityId },
            containsAttemptedOperations = dependent.any { it.status == "IN_FLIGHT" || it.attemptCount > 0 }
        )
    }

    @Transaction
    suspend fun prepareConflictDecision(ownerId: String, operationId: String, decisionKind: String): SyncConflictDecision {
        require(decisionKind in setOf("KEEP_LOCAL", "KEEP_SERVER", "MANUAL_REVIEW"))
        val target = blockedOperationById(ownerId, operationId)
            ?: error("Thao tác bị chặn không tồn tại hoặc không thuộc tài khoản")
        require(decisionKind in SyncConflictResolutionPlanner.allowedDecisions(target.operationType)) {
            "${target.operationType} chỉ được đánh dấu xử lý thủ công ở phiên bản hiện tại"
        }
        val existing = conflictDecision(ownerId, operationId)
        if (existing != null) {
            require(existing.decisionKind == decisionKind) { "Quyết định đã lưu không thể bị thay đổi" }
            return existing
        }
        val preflight = inspectBlockedResolution(ownerId, operationId)
            ?: error("Thao tác bị chặn không còn tồn tại hoặc không thuộc tài khoản")
        require(preflight.sameEntityOnly && !preflight.containsAttemptedOperations) {
            "Chuỗi phụ thuộc cần rà soát thủ công trước khi lưu quyết định"
        }
        // A non-manual choice is executable only for a truly isolated queue head.
        // Even same-entity, never-attempted successors may encode stock changes or
        // a delete; rebasing/replaying them is not implemented and must not be implied.
        if (decisionKind != "MANUAL_REVIEW") {
            require(preflight.dependentOperations.isEmpty()) {
                "Có ${preflight.dependentOperations.size} giao dịch phía sau; chỉ được đánh dấu xử lý thủ công, không tự thay thế hoặc bỏ chuỗi"
            }
        }
        val decision = SyncConflictDecision(
            blockedOperationId = operationId,
            ownerId = ownerId,
            decisionKind = decisionKind,
            planJson = JSONObject().put("operation_type", preflight.blocker.operationType)
                .put("entity_id", preflight.blocker.entityId)
                .put("dependency_count", preflight.dependentOperations.size).toString(),
            dependencyCount = preflight.dependentOperations.size
        )
        insertConflictDecision(decision)
        return decision
    }

    @Transaction
    suspend fun validatePreparedConflictDecision(ownerId: String, operationId: String): SyncConflictDecision {
        val decision = conflictDecision(ownerId, operationId) ?: error("Chưa có quyết định xử lý")
        require(decision.status == "PREPARED" || decision.status == "READY_FOR_SERVER_RECHECK")
        require(decision.decisionKind != "MANUAL_REVIEW") { "Quyết định yêu cầu xử lý thủ công" }
        val preflight = inspectBlockedResolution(ownerId, operationId)
            ?: error("Thao tác bị chặn không còn hợp lệ")
        require(preflight.sameEntityOnly && !preflight.containsAttemptedOperations)
        require(preflight.dependentOperations.size == decision.dependencyCount) {
            "Chuỗi thao tác phía sau đã thay đổi; cần kiểm tra lại"
        }
        if (decision.status == "PREPARED") check(markDecisionReady(ownerId, operationId) == 1)
        return conflictDecision(ownerId, operationId) ?: error("Không đọc lại được quyết định")
    }

    @Transaction
    suspend fun resolveIsolatedKeepServer(ownerId: String, operationId: String, serverPart: Part) {
        val decision = conflictDecision(ownerId, operationId) ?: error("Chưa lưu quyết định xử lý")
        require(decision.decisionKind == "KEEP_SERVER")
        require(decision.status == "READY_FOR_SERVER_RECHECK")
        val blocker = blockedOperationById(ownerId, operationId) ?: error("Thao tác gốc không còn BLOCKED")
        require(blocker.operationType == "PART_UPSERT")
        require(serverPart.ownerId == ownerId && serverPart.syncId == blocker.entityId)
        require(serverPart.serverVersion > 0L)
        require(partBySyncId(ownerId, blocker.entityId) != null) { "Không còn linh kiện local để đối soát" }
        val codeOwner = byCode(ownerId, serverPart.code)
        require(codeOwner == null || codeOwner.syncId == blocker.entityId) {
            "Mã linh kiện trên máy chủ đang được dùng bởi linh kiện local khác; cần xử lý thủ công"
        }
        val preflight = inspectBlockedResolution(ownerId, operationId)
            ?: error("Không đọc lại được chuỗi phụ thuộc")
        require(preflight.sameEntityOnly && !preflight.containsAttemptedOperations)
        require(preflight.dependentOperations.isEmpty() && decision.dependencyCount == 0) {
            "Có thao tác phía sau; không thể thay thế riêng lẻ bằng snapshot máy chủ"
        }
        check(activeRestore(ownerId) == null) { "Đang khôi phục dữ liệu; chưa thể đối soát" }
        upsertPulledPart(serverPart)
        check(markBlockedResolved(ownerId, operationId) == 1) { "Không thể đóng giao dịch xung đột" }
        check(updateConflictDecisionStatus(ownerId, operationId, "SERVER_APPLIED") == 1) {
            "Không thể ghi nhận quyết định đã áp dụng"
        }
    }

    @Query("UPDATE sync_conflict_decisions SET status = :status, updatedAt = :now WHERE ownerId = :ownerId AND blockedOperationId = :operationId AND status = 'READY_FOR_SERVER_RECHECK'")
    suspend fun updateConflictDecisionStatus(ownerId: String, operationId: String, status: String, now: Long = System.currentTimeMillis()): Int

    @Transaction
    suspend fun resolveIsolatedKeepLocal(ownerId: String, operationId: String, serverVersion: Long, serverQuantity: Int): SyncOutbox {
        require(serverVersion > 0L)
        val decision = conflictDecision(ownerId, operationId) ?: error("Chưa có quyết định")
        require(decision.decisionKind == "KEEP_LOCAL")
        if (decision.replacementOperationId.isNotBlank()) {
            return outboxById(decision.replacementOperationId)
                ?: error("Thiếu giao dịch thay thế đã liên kết; từ chối tạo bản trùng")
        }
        require(decision.status == "READY_FOR_SERVER_RECHECK")
        val blocker = blockedOperationById(ownerId, operationId) ?: error("Thao tác gốc không còn BLOCKED")
        require(blocker.operationType == "PART_UPSERT") { "Chỉ hỗ trợ PART_UPSERT độc lập ở bước này" }
        val dependents = queuedBehindBlocked(ownerId, operationId)
        require(dependents.isEmpty()) { "Có thao tác phụ thuộc phía sau; chưa thể thay thế riêng lẻ" }
        val part = partBySyncId(ownerId, blocker.entityId) ?: error("Không còn linh kiện local để ưu tiên")
        require(part.quantity == serverQuantity) {
            "Tồn kho local đã thay đổi trong lúc đối soát; không thể áp dụng quyết định metadata"
        }
        val newOperationId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("sync_id", part.syncId); put("code", part.code); put("name", part.name)
            put("quantity", part.quantity); put("min_quantity", part.minQuantity)
            put("unit", part.unit); put("photo_path", part.photoPath); put("note", part.note)
            put("tracking_mode", part.trackingMode); put("updated_at", part.updatedAt)
            put("expected_server_version", serverVersion)
        }.toString()
        val replacement = SyncOutbox(
            operationId = newOperationId, ownerId = ownerId, operationType = "PART_UPSERT",
            entityId = part.syncId, payloadJson = payload
        )
        enqueue(replacement)
        check(updatePartServerVersion(ownerId, part.syncId, serverVersion) == 1)
        check(markBlockedResolved(ownerId, operationId) == 1)
        check(linkReplacement(ownerId, operationId, newOperationId, serverVersion) == 1)
        return replacement
    }

    @Transaction
    suspend fun claimOutboxForSend(operationId: String): SyncOutbox? {
        val row = outboxById(operationId) ?: return null
        if (row.status != "PENDING" && row.status != "FAILED") return null
        if (firstBlockedOutbox(row.ownerId) != null) return null
        val earlier = earliestUnresolvedOutbox(row.ownerId) ?: return null
        if (earlier.operationId != operationId) return null
        if (hasInFlightOutbox(row.ownerId)) return null
        val part = partBySyncId(row.ownerId, row.entityId)
        val frozen = if (row.operationType == "PART_UPSERT" || row.operationType == "PART_DELETE" || row.operationType == "STOCK_CHANGE") {
            val json = JSONObject(row.payloadJson)
            if (!json.has("expected_server_version")) json.put("expected_server_version", part?.serverVersion ?: 0L)
            json.toString()
        } else row.payloadJson
        if (frozen != row.payloadJson) updateQueuedPayload(row.operationId, frozen)
        if (markOutboxInFlight(row.operationId) != 1) return null
        return outboxById(operationId)
    }

    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED') ORDER BY createdAt, rowid LIMIT 1")
    suspend fun earliestUnresolvedOutbox(ownerId: String): SyncOutbox?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId = :ownerId AND status = 'IN_FLIGHT')")
    suspend fun hasInFlightOutbox(ownerId: String): Boolean


    @Transaction
    suspend fun recordServerVersionAndRebase(ownerId: String, entityId: String, version: Long) {
        require(version > 0L) { "server_version không hợp lệ" }
        check(updatePartServerVersion(ownerId, entityId, version) == 1) {
            "Không tìm thấy linh kiện để ghi nhận server_version đã xác nhận"
        }
        // Each queued mutation increments the server version exactly once.
        // Assign a distinct expected version in FIFO order; giving every row the
        // same base version makes the second operation conflict after the first commits.
        var nextExpectedVersion = version
        queuedPartOperations(ownerId, entityId).forEach { row ->
            val json = JSONObject(row.payloadJson)
            json.put("expected_server_version", nextExpectedVersion)
            check(updateQueuedPayload(row.operationId, json.toString()) == 1) {
                "Không thể cập nhật phiên bản cho giao dịch đang chờ"
            }
            nextExpectedVersion = Math.addExact(nextExpectedVersion, 1L)
        }
    }

    @Query("DELETE FROM parts WHERE ownerId = :ownerId AND syncId = :syncId")
    suspend fun deletePulledPart(ownerId: String, syncId: String)

    @Query("SELECT * FROM part_tombstones WHERE ownerId = :ownerId AND syncId = :syncId LIMIT 1")
    suspend fun partTombstone(ownerId: String, syncId: String): PartTombstone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePartTombstone(tombstone: PartTombstone)

    @Query("DELETE FROM part_tombstones WHERE ownerId = :ownerId AND syncId = :syncId")
    suspend fun clearPartTombstone(ownerId: String, syncId: String): Int

    @Query("DELETE FROM part_tombstones WHERE ownerId = :ownerId AND EXISTS (SELECT 1 FROM parts p WHERE p.ownerId = part_tombstones.ownerId AND p.syncId = part_tombstones.syncId AND p.serverVersion > part_tombstones.serverVersion)")
    suspend fun clearSupersededPartTombstones(ownerId: String): Int

    @Transaction
    suspend fun applyPulledPart(ownerId: String, part: Part, deleted: Boolean): Boolean {
        // Serialize pull writes with restore-journal creation. A worker-level check
        // alone is insufficient because a restore can begin after that check.
        if (activeRestore(ownerId) != null) return false
        require(ownerId.isNotBlank() && part.ownerId == ownerId && part.syncId.isNotBlank()) {
            "Change feed PART không thuộc owner/entity hợp lệ"
        }
        require(part.serverVersion > 0L) { "Change feed PART thiếu server_version dương" }
        if (hasUnsentOperation(ownerId, part.syncId)) return false
        val existing = partBySyncId(ownerId, part.syncId)
        val tombstone = partTombstone(ownerId, part.syncId)
        // Compare against both live-row and durable tombstone versions. The
        // tombstone survives physical deletion and process death, preventing a
        // delayed/replayed older upsert from resurrecting a deleted part.
        val highestKnownVersion = maxOf(existing?.serverVersion ?: 0L, tombstone?.serverVersion ?: 0L)
        if (highestKnownVersion >= part.serverVersion) return true
        if (deleted) {
            savePartTombstone(PartTombstone(ownerId, part.syncId, part.serverVersion))
            deletePulledPart(ownerId, part.syncId)
        } else {
            // A strictly newer live event is an explicit server-side recreation;
            // remove the older tombstone only in the same transaction as upsert.
            clearPartTombstone(ownerId, part.syncId)
            // photo_path from change feed is a Supabase Storage object path.
            // Never overwrite a cloud path with another device's local cache path.
            upsertPulledPart(part.copy(id = existing?.id ?: 0L, ownerId = ownerId))
        }
        return true
    }

    private suspend fun enqueuePart(ownerId: String, type: String, part: Part, previousPhotoPath: String = "") {
        val payload = JSONObject().apply {
            put("sync_id", part.syncId); put("code", part.code); put("name", part.name)
            put("quantity", part.quantity); put("min_quantity", part.minQuantity)
            put("unit", part.unit); put("photo_path", part.photoPath); put("note", part.note)
            put("tracking_mode", part.trackingMode); put("updated_at", part.updatedAt)
            put("expected_server_version", part.serverVersion)
            put("previous_photo_path", previousPhotoPath)
        }.toString()
        enqueue(SyncOutbox(ownerId = ownerId, operationType = type, entityId = part.syncId, payloadJson = payload))
    }
    @Query("SELECT * FROM parts WHERE ownerId = :ownerId ORDER BY name COLLATE NOCASE")
    fun partsForOwner(ownerId: String): Flow<List<Part>>

    @Query("SELECT * FROM parts WHERE code = :code AND ownerId = :ownerId LIMIT 1")
    suspend fun byCode(ownerId: String, code: String): Part?

    @Query("SELECT * FROM parts WHERE id = :id AND ownerId = :ownerId LIMIT 1")
    suspend fun byId(ownerId: String, id: Long): Part?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(part: Part): Long

    // Ghi linh kiện và tồn đầu kỳ trong cùng một transaction.
    // Nếu ghi lịch sử thất bại, thao tác thêm linh kiện cũng được rollback.
    @Transaction
    suspend fun insertPartWithOpeningMovement(part: Part, ownerName: String) {
        check(activeRestore(part.ownerId) == null) {
            "Đang có phiên khôi phục chưa hoàn tất; không thể thêm linh kiện lúc này."
        }
        insert(part)
        enqueuePart(part.ownerId, "PART_UPSERT", part)
        if (part.quantity > 0) {
            val opening = Movement(
                    code = part.code,
                    partName = part.name,
                    type = "ĐẦU KỲ",
                    amount = part.quantity,
                    beforeQty = 0,
                    afterQty = part.quantity,
                    reason = "Tồn ban đầu",
                    ownerId = part.ownerId,
                    ownerName = ownerName
                )
            addMovement(opening)
            val movementPayload = JSONObject().apply {
                put("type", opening.type); put("amount", opening.amount)
                put("before_qty", opening.beforeQty); put("after_qty", opening.afterQty)
                put("movement_sync_id", opening.syncId); put("part_sync_id", part.syncId)
                put("code", part.code); put("reason", opening.reason)
                put("expected_server_version", part.serverVersion)
            }.toString()
            enqueue(SyncOutbox(ownerId = part.ownerId, operationType = "STOCK_CHANGE", entityId = part.syncId, payloadJson = movementPayload))
        }
    }

    @Update
    suspend fun updateRow(part: Part): Int

    @Transaction
    suspend fun update(ownerId: String, part: Part) {
        require(ownerId.isNotBlank() && part.ownerId == ownerId) {
            "Linh kiện không thuộc tài khoản đang đăng nhập"
        }
        check(activeRestore(ownerId) == null) {
            "Đang có phiên khôi phục chưa hoàn tất; không thể sửa linh kiện lúc này."
        }
        val previous = byId(ownerId, part.id)
            ?: error("Không tìm thấy linh kiện thuộc tài khoản hiện tại")
        require(part.syncId == previous.syncId && part.code == previous.code) {
            "Không được thay đổi định danh hoặc mã QR của linh kiện khi sửa thông tin"
        }
        require(part.serverVersion == previous.serverVersion) {
            "Không được tự thay đổi phiên bản máy chủ của linh kiện"
        }
        require(part.quantity == previous.quantity) {
            "Không được sửa tồn kho trực tiếp tại màn hình thông tin; hãy dùng Nhập, Xuất hoặc Kiểm kê để lưu lịch sử"
        }
        require(part.name.isNotBlank()) { "Tên linh kiện không được để trống" }
        require(part.minQuantity >= 0) { "Ngưỡng cảnh báo không được âm" }
        require(part.unit.isNotBlank()) { "Đơn vị không được để trống" }
        require(part.trackingMode == "SINGLE" || part.trackingMode == "BULK") {
            "Chế độ theo dõi QR không hợp lệ"
        }
        require(previous.trackingMode != "BULK" || part.trackingMode != "SINGLE" || previous.quantity <= 1) {
            "Không thể chuyển sang Linh kiện lẻ khi tồn kho lớn hơn 1; hãy kiểm kê/chia mã trước"
        }
        check(updateRow(part) == 1) { "Không cập nhật được linh kiện; dữ liệu có thể đã thay đổi" }
        enqueuePart(ownerId, "PART_UPSERT", part, previous.photoPath)
    }

    @Query("SELECT * FROM movements WHERE ownerId = :ownerId ORDER BY time DESC, id DESC")
    fun movementsForOwner(ownerId: String): Flow<List<Movement>>

    // Admin history is loaded with bounded keyset pages. The local auto-increment
    // ID is the deterministic tie-breaker for movements sharing the same timestamp.
    @Query("SELECT * FROM movements ORDER BY time DESC, id DESC LIMIT :limit")
    suspend fun latestAdminMovementPage(limit: Int): List<Movement>

    // Observe only a scalar insertion watermark, not the full history. It also
    // detects delayed sync rows whose event timestamp is older than the head.
    @Query("SELECT MAX(id) FROM movements")
    fun observeMovementMaxId(): Flow<Long?>

    // Snapshot the insertion watermark before loading a page. Capturing it
    // after the page query could hide a delayed row inserted between the two.
    @Query("SELECT MAX(id) FROM movements")
    suspend fun currentMovementMaxId(): Long?

    @Query("SELECT * FROM movements WHERE time < :beforeTime OR (time = :beforeTime AND id < :beforeId) ORDER BY time DESC, id DESC LIMIT :limit")
    suspend fun olderAdminMovementPage(beforeTime: Long, beforeId: Long, limit: Int): List<Movement>

    @Query("SELECT * FROM movements WHERE type = 'XUẤT' AND lower(machineOrStore) = lower(:machineCode) AND (:ownerId IS NULL OR ownerId = :ownerId) ORDER BY time DESC, id DESC")
    suspend fun machineHistoryMovements(ownerId: String?, machineCode: String): List<Movement>

    @Insert
    suspend fun addMovement(movement: Movement)

    @Query("DELETE FROM movements WHERE ownerId = :ownerId")
    suspend fun clearMovements(ownerId: String)

    @Query("DELETE FROM parts WHERE ownerId = :ownerId")
    suspend fun clearParts(ownerId: String)

    @Query("DELETE FROM parts WHERE code = :code AND ownerId = :ownerId")
    suspend fun deletePartRow(ownerId: String, code: String)

    @Transaction
    suspend fun deletePartByCode(ownerId: String, code: String) {
        check(activeRestore(ownerId) == null) {
            "Đang có phiên khôi phục chưa hoàn tất; không thể xóa linh kiện lúc này."
        }
        val part = byCode(ownerId, code) ?: return
        val payload = JSONObject().put("sync_id", part.syncId).put("code", code)
            .put("photo_path", part.photoPath)
            .put("expected_server_version", part.serverVersion).toString()
        enqueue(SyncOutbox(ownerId = ownerId, operationType = "PART_DELETE", entityId = part.syncId, payloadJson = payload))
        deletePartRow(ownerId, code)
    }

    @Insert
    suspend fun insertMovement(movement: Movement)

    @Insert
    suspend fun insertPart(part: Part)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParts(parts: List<Part>)

    @Insert
    suspend fun insertMovements(movements: List<Movement>)

    /**
     * Atomically replaces only one owner's local inventory with a server-confirmed
     * snapshot. All snapshot rows are validated before the first destructive query.
     * The operation is chunked to avoid materializing the complete inventory in RAM.
     */
    @Transaction
    suspend fun applyCommittedRestoreSnapshot(
        ownerId: String,
        restoreId: String,
        snapshotCursor: Long,
        expectedPartCount: Int,
        expectedMovementCount: Int
    ) {
        require(ownerId.isNotBlank() && restoreId.isNotBlank()) { "Thiếu tài khoản hoặc restoreId" }
        UUID.fromString(ownerId)
        require(expectedPartCount >= 0 && expectedMovementCount >= 0) { "Số lượng snapshot âm" }
        require(snapshotCursor > 0L) { "Snapshot cursor không hợp lệ" }
        val journal = restoreJournal(ownerId, restoreId) ?: error("Không tìm thấy journal khôi phục")
        require(journal.ownerId == ownerId) { "Journal không thuộc tài khoản hiện tại" }
        require(journal.status == "SNAPSHOT_DOWNLOADED") { "Snapshot chưa tải và kiểm tra đầy đủ" }
        require(journal.snapshotCursor == snapshotCursor) { "Snapshot cursor không khớp journal" }
        require(journal.partCount == expectedPartCount && journal.movementCount == expectedMovementCount) {
            "Số lượng snapshot không khớp journal"
        }
        require(countUnsentOutbox(ownerId) == 0) {
            "Còn giao dịch chưa đồng bộ. Không thể thay kho an toàn; cần xử lý outbox trước."
        }

        val partChunkIndexes = stagedRestoreChunkIndexes(ownerId, restoreId, "SNAPSHOT_PART")
        val movementChunkIndexes = stagedRestoreChunkIndexes(ownerId, restoreId, "SNAPSHOT_MOVEMENT")
        require(partChunkIndexes == partChunkIndexes.indices.toList()) { "Chunk linh kiện snapshot không liên tục" }
        require(movementChunkIndexes == movementChunkIndexes.indices.toList()) { "Chunk lịch sử snapshot không liên tục" }
        require(countStagedRestoreRows(ownerId, restoreId, "SNAPSHOT_PART") == expectedPartCount) { "Thiếu hoặc thừa dòng linh kiện snapshot" }
        require(countStagedRestoreRows(ownerId, restoreId, "SNAPSHOT_MOVEMENT") == expectedMovementCount) { "Thiếu hoặc thừa dòng lịch sử snapshot" }

        val partIds = HashSet<String>(expectedPartCount)
        val partCodes = HashSet<String>(expectedPartCount)
        partChunkIndexes.forEach { chunkIndex ->
            val rows = stagedRestoreChunk(ownerId, restoreId, "SNAPSHOT_PART", chunkIndex)
            require(rows.size in 1..200) { "Chunk linh kiện $chunkIndex có số dòng không hợp lệ" }
            require(rows.map { it.rowIndex } == rows.indices.toList()) { "Thứ tự dòng linh kiện không hợp lệ" }
            rows.forEach { row ->
                val json = JSONObject(row.payloadJson)
                val syncId = UUID.fromString(json.getString("sync_id")).toString()
                val code = json.getString("code").trim()
                val name = json.getString("name").trim()
                require(code.isNotEmpty() && name.isNotEmpty()) { "Linh kiện thiếu mã hoặc tên" }
                require(json.getInt("quantity") >= 0 && json.optInt("min_quantity", 0) >= 0) { "Số lượng linh kiện không hợp lệ" }
                require(json.optLong("server_version", 0L) > 0L) { "Snapshot linh kiện thiếu server_version dương" }
                require(json.optString("tracking_mode", "SINGLE") in setOf("SINGLE", "BULK")) { "Chế độ theo dõi linh kiện không hợp lệ" }
                val photoPath = json.optString("photo_path", "")
                require(photoPath.isBlank() || (photoPath.startsWith("$ownerId/") && !photoPath.contains("\\") && photoPath.split('/').none { it.isBlank() || it == "." || it == ".." })) {
                    "Đường dẫn ảnh snapshot không thuộc tài khoản hoặc không an toàn"
                }
                require(partIds.add(syncId)) { "Snapshot có sync_id linh kiện trùng" }
                require(partCodes.add(code)) { "Snapshot có mã linh kiện trùng" }
            }
        }
        require(partIds.size == expectedPartCount) { "Tổng linh kiện snapshot không khớp" }

        val movementIds = HashSet<String>(expectedMovementCount)
        movementChunkIndexes.forEach { chunkIndex ->
            val rows = stagedRestoreChunk(ownerId, restoreId, "SNAPSHOT_MOVEMENT", chunkIndex)
            require(rows.size in 1..200) { "Chunk lịch sử $chunkIndex có số dòng không hợp lệ" }
            require(rows.map { it.rowIndex } == rows.indices.toList()) { "Thứ tự dòng lịch sử không hợp lệ" }
            rows.forEach { row ->
                val json = JSONObject(row.payloadJson)
                val syncId = UUID.fromString(json.getString("sync_id")).toString()
                // Movement history is retained after a part is deleted. Validate
                // its own immutable code/name/quantity fields without requiring a
                // matching row in the current parts catalog.
                val code = json.getString("code").trim()
                require(code.isNotEmpty()) { "Lịch sử thiếu mã linh kiện" }
                val movementType = json.getString("type")
                require(json.getString("part_name").isNotBlank() && movementType in setOf("NHẬP", "XUẤT", "KIỂM KÊ", "ĐẦU KỲ")) {
                    "Lịch sử thiếu tên hoặc loại giao dịch hợp lệ"
                }
                val amount = json.getInt("amount")
                val beforeQty = json.getInt("before_qty")
                val afterQty = json.getInt("after_qty")
                require(amount >= 0 && beforeQty >= 0 && afterQty >= 0 && json.getLong("time") > 0L) {
                    "Số lượng hoặc thời gian lịch sử không hợp lệ"
                }
                require(movementType == "KIỂM KÊ" || amount > 0) {
                    "Số lượng giao dịch nhập/xuất/đầu kỳ phải lớn hơn 0"
                }
                when (movementType) {
                    "NHẬP" -> require(afterQty.toLong() == beforeQty.toLong() + amount.toLong()) {
                        "Lịch sử nhập kho không khớp số lượng trước/sau"
                    }
                    "XUẤT" -> require(beforeQty >= amount && afterQty.toLong() == beforeQty.toLong() - amount.toLong()) {
                        "Lịch sử xuất kho không khớp số lượng trước/sau"
                    }
                    "KIỂM KÊ" -> require(afterQty == amount) { "Lịch sử kiểm kê không khớp số lượng sau kiểm kê" }
                    "ĐẦU KỲ" -> require(beforeQty == 0 && afterQty == amount) { "Lịch sử đầu kỳ không hợp lệ" }
                }
                require(movementIds.add(syncId)) { "Snapshot có sync_id lịch sử trùng" }
            }
        }
        require(movementIds.size == expectedMovementCount) { "Tổng lịch sử snapshot không khớp" }

        // No live data is touched until all chunks and cross-references pass validation.
        clearMovements(ownerId)
        clearParts(ownerId)
        partChunkIndexes.forEach { chunkIndex ->
            val entities = stagedRestoreChunk(ownerId, restoreId, "SNAPSHOT_PART", chunkIndex).map { row ->
                val json = JSONObject(row.payloadJson)
                Part(
                    code = json.getString("code"), name = json.getString("name"),
                    quantity = json.getInt("quantity"), minQuantity = json.optInt("min_quantity", 0),
                    unit = json.optString("unit", "cái"), photoPath = json.optString("photo_path", ""),
                    note = json.optString("note", ""), updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
                    trackingMode = json.optString("tracking_mode", "SINGLE"), ownerId = ownerId,
                    syncId = UUID.fromString(json.getString("sync_id")).toString(),
                    serverVersion = json.optLong("server_version", 0L)
                )
            }
            insertParts(entities)
        }
        // Keep tombstones for parts absent from the live snapshot. If a restored
        // live row has a strictly newer version, it supersedes its old tombstone.
        clearSupersededPartTombstones(ownerId)
        movementChunkIndexes.forEach { chunkIndex ->
            val entities = stagedRestoreChunk(ownerId, restoreId, "SNAPSHOT_MOVEMENT", chunkIndex).map { row ->
                val json = JSONObject(row.payloadJson)
                Movement(
                    code = json.getString("code"), partName = json.getString("part_name"),
                    type = json.getString("type"), amount = json.getInt("amount"),
                    beforeQty = json.getInt("before_qty"), afterQty = json.getInt("after_qty"),
                    reason = json.optString("reason", ""), machineOrStore = json.optString("machine_or_store", ""),
                    time = json.getLong("time"), ownerId = ownerId, ownerName = "",
                    syncId = UUID.fromString(json.getString("sync_id")).toString()
                )
            }
            insertMovements(entities)
        }
        saveSyncCursor(SyncCursor(ownerId = ownerId, lastEventId = snapshotCursor))
        saveRestoreJournal(journal.copy(status = "COMPLETE", lastError = "", updatedAt = System.currentTimeMillis()))
    }

    // Dữ liệu ở các bản cũ chưa có ownerId. Chỉ gán một lần cho tài khoản
    // đầu tiên đăng nhập sau khi nâng cấp; không chuyển dữ liệu giữa các KTV.
    @Query("UPDATE parts SET ownerId = :ownerId WHERE ownerId = ''")
    suspend fun claimLegacyPartsRows(ownerId: String): Int

    @Transaction
    suspend fun claimLegacyParts(ownerId: String): Int {
        check(activeRestore(ownerId) == null) {
            "Đang có phiên khôi phục chưa hoàn tất; chưa thể nhận dữ liệu kho cũ."
        }
        return claimLegacyPartsRows(ownerId)
    }

    @Query("UPDATE movements SET ownerId = :ownerId WHERE ownerId = ''")
    suspend fun claimLegacyMovementRows(ownerId: String): Int

    @Transaction
    suspend fun claimLegacyMovements(ownerId: String): Int {
        check(activeRestore(ownerId) == null) {
            "Đang có phiên khôi phục chưa hoàn tất; chưa thể nhận lịch sử cũ."
        }
        return claimLegacyMovementRows(ownerId)
    }

    @Transaction
    suspend fun restoreAll(ownerId: String, newParts: List<Part>, newMovements: List<Movement>) {
        check(activeRestore(ownerId) == null) {
            "Đang có phiên khôi phục cloud chưa hoàn tất; không thể chạy khôi phục cũ."
        }
        clearMovements(ownerId)
        clearParts(ownerId)
        newParts.forEach { insertPart(it.copy(id = 0, ownerId = ownerId)) }
        newMovements.forEach { insertMovement(it.copy(id = 0, ownerId = ownerId)) }
    }

    @Transaction
    suspend fun changeStock(ownerId: String, ownerName: String, code: String, type: String, amount: Int, reason: String, machine: String) {
        check(activeRestore(ownerId) == null) {
            "Đang có phiên khôi phục chưa hoàn tất; không thể thay đổi tồn kho lúc này."
        }
        // Live stock changes are deliberately narrower than imported history:
        // opening balance is created only by insertPartWithOpeningMovement().
        require(type in setOf("NHẬP", "XUẤT", "KIỂM KÊ")) {
            "Loại giao dịch tồn kho không được hỗ trợ"
        }
        require(if (type == "KIỂM KÊ") amount >= 0 else amount > 0) {
            if (type == "KIỂM KÊ") "Số lượng kiểm kê không được âm" else "Số lượng phải lớn hơn 0"
        }
        val part = byCode(ownerId, code) ?: error("Không tìm thấy mã linh kiện: $code")
        require(part.quantity >= 0) { "Tồn kho hiện tại không hợp lệ; cần đối soát trước khi thay đổi" }
        if (type == "XUẤT" && part.trackingMode == "SINGLE") {
            require(amount == 1) { "Linh kiện lẻ chỉ được xuất từng 1 món" }
            require(part.quantity > 0) { "Linh kiện lẻ này đã xuất hoặc đã hết tồn" }
        }
        val afterLong = when (type) {
            "NHẬP" -> part.quantity.toLong() + amount.toLong()
            "XUẤT" -> part.quantity.toLong() - amount.toLong()
            "KIỂM KÊ" -> amount.toLong()
            else -> error("Loại giao dịch tồn kho không được hỗ trợ")
        }
        require(afterLong in 0L..Int.MAX_VALUE.toLong()) {
            if (type == "XUẤT") "Tồn kho không đủ. Hiện còn ${part.quantity} ${part.unit}"
            else "Số lượng tồn vượt giới hạn cho phép"
        }
        val after = afterLong.toInt()
        update(ownerId, part.copy(quantity = after, updatedAt = System.currentTimeMillis()))
        val movement = Movement(code = code, partName = part.name, type = type, amount = amount,
            beforeQty = part.quantity, afterQty = after, reason = reason, machineOrStore = machine, ownerId = ownerId, ownerName = ownerName)
        insertMovement(movement)
        val payload = JSONObject().apply {
            put("type", type); put("amount", amount); put("reason", reason)
            put("machine_or_store", machine); put("movement_sync_id", movement.syncId)
            put("part_sync_id", part.syncId); put("code", code)
            put("expected_server_version", part.serverVersion)
        }.toString()
        enqueue(SyncOutbox(ownerId = ownerId, operationType = "STOCK_CHANGE", entityId = part.syncId, payloadJson = payload))
    }
}

@Database(entities = [Part::class, Movement::class, SyncOutbox::class, SyncCursor::class, PartTombstone::class, SyncConflictDecision::class, RestoreJournal::class, RestoreStagedRow::class, WeeklyBackupJournal::class], version = 14, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): InventoryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parts ADD COLUMN trackingMode TEXT NOT NULL DEFAULT 'SINGLE'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parts ADD COLUMN ownerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE movements ADD COLUMN ownerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS index_parts_code")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_parts_ownerId_code ON parts(ownerId, code)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_movements_ownerId_time ON movements(ownerId, time)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movements ADD COLUMN ownerName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parts ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE movements ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE parts SET syncId = lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))),2) || '-a' || substr(lower(hex(randomblob(2))),2) || '-' || lower(hex(randomblob(6))) WHERE syncId = ''")
                db.execSQL("UPDATE movements SET syncId = lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))),2) || '-a' || substr(lower(hex(randomblob(2))),2) || '-' || lower(hex(randomblob(6))) WHERE syncId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_parts_syncId ON parts(syncId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_movements_syncId ON movements(syncId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (operationId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, operationType TEXT NOT NULL, entityId TEXT NOT NULL, payloadJson TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL, attemptCount INTEGER NOT NULL, lastError TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_ownerId_status_createdAt ON sync_outbox(ownerId, status, createdAt)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_cursors (ownerId TEXT NOT NULL PRIMARY KEY, lastEventId INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS restore_journal (restoreId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, manifestSha256 TEXT NOT NULL, status TEXT NOT NULL, stagedDirectory TEXT NOT NULL, partCount INTEGER NOT NULL, movementCount INTEGER NOT NULL, snapshotCursor INTEGER NOT NULL, lastError TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_journal_ownerId_status ON restore_journal(ownerId, status)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS restore_staged_rows (restoreId TEXT NOT NULL, ownerId TEXT NOT NULL, entityType TEXT NOT NULL, chunkIndex INTEGER NOT NULL, rowIndex INTEGER NOT NULL, payloadJson TEXT NOT NULL, PRIMARY KEY(restoreId, entityType, chunkIndex, rowIndex))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_staged_rows_ownerId_restoreId_entityType_chunkIndex ON restore_staged_rows(ownerId, restoreId, entityType, chunkIndex)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parts ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN claimStartedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflict_decisions (blockedOperationId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, decisionKind TEXT NOT NULL, status TEXT NOT NULL, planJson TEXT NOT NULL, dependencyCount INTEGER NOT NULL, replacementOperationId TEXT NOT NULL DEFAULT '', replacementServerVersion INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_conflict_decisions_ownerId_status ON sync_conflict_decisions(ownerId, status)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS part_tombstones (ownerId TEXT NOT NULL, syncId TEXT NOT NULL, serverVersion INTEGER NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(ownerId, syncId))")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE restore_journal ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'LOCAL_ZIP'")
                db.execSQL("ALTER TABLE restore_journal ADD COLUMN partChunkCount INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE restore_journal ADD COLUMN movementChunkCount INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS weekly_backup_journal (backupId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, state TEXT NOT NULL, archivePath TEXT NOT NULL, createdAt INTEGER NOT NULL, snapshotCursor INTEGER NOT NULL, partCount INTEGER NOT NULL, movementCount INTEGER NOT NULL, archiveSizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, lastError TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_backup_journal_ownerId_state ON weekly_backup_journal(ownerId, state)")
            }
        }

        @Volatile private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "kho-linh-kien.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                .build().also { INSTANCE = it }
        }
    }
}
