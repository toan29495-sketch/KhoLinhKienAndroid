package vn.kholinhkien

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.io.File

/** Pulls the owner's append-only change feed. Cursor advances only after a page event is applied. */
class SyncPullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Serialize the entire pull run with ZIP import/resume/abort. If a restore
        // owns the gate, leave the cursor untouched and let WorkManager retry later.
        if (!RestoreOperationGate.mutex.tryLock()) return Result.retry()
        return try {
            doWorkWhileRestoreGateHeld()
        } finally {
            RestoreOperationGate.mutex.unlock()
        }
    }

    private suspend fun doWorkWhileRestoreGateHeld(): Result {
        val ownerId = inputData.getString(KEY_OWNER_ID) ?: return Result.failure()
        val isAdmin = inputData.getBoolean(KEY_IS_ADMIN, false)
        val cursorKey = if (isAdmin) "admin-global:$ownerId" else ownerId
        val client = SupabaseClientProvider.client
        return try {
            val currentUser = client.auth.currentUserOrNull()?.id ?: return Result.success()
            if (currentUser != ownerId) return Result.success()
            val dao = AppDb.get(applicationContext).dao()
            if (isAdmin && !hasActiveAdminProfile(ownerId)) {
                // A queued Admin worker may outlive a server-side role downgrade.
                // Purge only foreign-owner cache and reset only its global cursor.
                dao.clearForeignMovementCache(ownerId)
                dao.clearSyncCursor(cursorKey)
                return Result.success()
            }
            val activeRestore = dao.activeRestore(ownerId)
            if (activeRestore != null) {
                if (activeRestore.sourceType != "REMOTE_FEED") return Result.retry()
                RestoreSnapshotGate.mutex.withLock {
                    // The initial query may have gone stale while another restore
                    // coordinator/worker owned the shared snapshot gate.
                    val latest = dao.restoreJournal(ownerId, activeRestore.restoreId)
                        ?: return@withLock
                    if (latest.ownerId != ownerId || latest.sourceType != "REMOTE_FEED") return Result.retry()
                    if (dao.countUnsentOutbox(ownerId) != 0) return Result.retry()
                    when (latest.status) {
                        "REMOTE_COMMIT_CONFIRMED", "SNAPSHOT_DOWNLOADING" ->
                            RestoreCloudSnapshotReader(applicationContext, dao).download(ownerId, latest.restoreId)
                        "SNAPSHOT_DOWNLOADED" -> Unit
                        "COMPLETE" -> Unit
                        else -> return Result.retry()
                    }
                    val beforeApply = dao.restoreJournal(ownerId, latest.restoreId)
                        ?: return@withLock
                    if (beforeApply.status == "SNAPSHOT_DOWNLOADED") {
                        if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()
                        RestoreCloudSnapshotApplier(applicationContext, dao).apply(ownerId, latest.restoreId)
                    } else if (beforeApply.status != "COMPLETE") {
                        return Result.retry()
                    }
                }
            }
            var cursor = dao.getSyncCursor(cursorKey) ?: 0L
            var pages = 0
            var blockedByLocalChanges = false
            var pageLimitReached = false
            while (pages < MAX_PAGES_PER_RUN) {
                pages++
                val response = client.postgrest.rpc(
                    if (isAdmin) "pull_admin_movement_changes" else "pull_inventory_changes",
                    parameters = buildJsonObject {
                        put("p_after_event_id", cursor)
                        put("p_limit", PAGE_SIZE)
                    }
                )
                // The shared client session can change while the RPC is in flight.
                // Discard this page before mapping it into the worker's captured owner.
                if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()
                if (isAdmin && !hasActiveAdminProfile(ownerId)) {
                    dao.clearForeignMovementCache(ownerId)
                    dao.clearSyncCursor(cursorKey)
                    return Result.success()
                }
                val root = Json.parseToJsonElement(response.toString()).jsonObject
                val events = root["events"]?.jsonArray ?: break
                if (events.isEmpty()) break
                for (eventElement in events) {
                    // Re-check for every event: do not apply a page fetched under a
                    // different session to the owner partition captured at startup.
                    if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()
                    val event = eventElement.jsonObject
                    val eventId = event["event_id"]?.jsonPrimitive?.longOrNull
                        ?: error("Change feed thiếu event_id")
                    val entityType = event["entity_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val payload = event["payload"]?.jsonObject ?: error("Change feed thiếu payload")
                    when (entityType) {
                        // Admin global feed uses a redacted SKIP marker for PART events.
                        // This preserves the contiguous global cursor without exposing
                        // another user's inventory payload to the Admin client.
                        "SKIP" -> Unit
                        "PART" -> {
                            if (isAdmin) {
                                cursor = eventId
                                if (!dao.savePulledCursorIfUnlocked(ownerId, SyncCursor(cursorKey, cursor))) {
                                    blockedByLocalChanges = true
                                    break
                                }
                                continue
                            }
                            val part = payload.toLocalPart(ownerId)
                            val deleted = payload["deleted_at"] != null && payload["deleted_at"] != JsonNull
                            if (!dao.applyPulledPart(ownerId, part, deleted)) {
                                // Do not advance past this event: let the outbox finish first,
                                // then replay this snapshot and all later events in order.
                                blockedByLocalChanges = true
                                break
                            }
                        }
                        "MOVEMENT" -> {
                            val movement = payload.toLocalMovement(ownerId, allowForeignOwner = isAdmin)
                            val applied = if (isAdmin) {
                                dao.applyPulledAdminMovement(ownerId, movement)
                            } else {
                                dao.applyPulledMovement(ownerId, movement)
                            }
                            if (!applied) {
                                // A restore may have started while this page was in flight.
                                // Do not advance the cursor; replay after restore finishes.
                                blockedByLocalChanges = true
                                break
                            }
                        }
                        "RESTORE_COMMIT" -> {
                            // The Admin-global feed is a separate history cursor. It may
                            // contain another KTV's restore marker, but must never replace
                            // the Admin's personal inventory.
                            val restoreId = payload["restore_id"]?.jsonPrimitive?.contentOrNull
                                ?: error("RESTORE_COMMIT thiếu restore_id")
                            require(runCatching { java.util.UUID.fromString(restoreId) }.isSuccess) {
                                "RESTORE_COMMIT có restore_id không hợp lệ"
                            }
                            val digest = payload["manifest_sha256"]?.jsonPrimitive?.contentOrNull
                                ?: error("RESTORE_COMMIT thiếu manifest_sha256")
                            require(digest.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                                "RESTORE_COMMIT có manifest_sha256 không hợp lệ"
                            }
                            val partCount = payload["part_count"]?.jsonPrimitive?.intOrNull
                                ?: error("RESTORE_COMMIT thiếu part_count")
                            val movementCount = payload["movement_count"]?.jsonPrimitive?.intOrNull
                                ?: error("RESTORE_COMMIT thiếu movement_count")
                            val partChunks = payload["part_chunks"]?.jsonPrimitive?.intOrNull
                                ?: error("RESTORE_COMMIT thiếu part_chunks")
                            val movementChunks = payload["movement_chunks"]?.jsonPrimitive?.intOrNull
                                ?: error("RESTORE_COMMIT thiếu movement_chunks")
                            require(partCount in 0..500000 && movementCount in 0..1000000) {
                                "RESTORE_COMMIT có số lượng bản ghi vượt giới hạn"
                            }
                            require(partChunks in 0..minOf(partCount, 8192) &&
                                movementChunks in 0..minOf(movementCount, 8192) &&
                                ((partCount == 0) == (partChunks == 0)) &&
                                ((movementCount == 0) == (movementChunks == 0))) {
                                "RESTORE_COMMIT có số chunk không hợp lệ"
                            }
                            if (!isAdmin) {
                                val existing = dao.restoreJournal(ownerId, restoreId)
                                if (existing?.status == "COMPLETE") {
                                    require(existing.sourceType == "LOCAL_ZIP" || existing.sourceType == "REMOTE_FEED")
                                    require(existing.manifestSha256.equals(digest, ignoreCase = true) &&
                                        existing.snapshotCursor == eventId && existing.partCount == partCount &&
                                        existing.movementCount == movementCount) {
                                        "Journal COMPLETE không khớp RESTORE_COMMIT"
                                    }
                                } else {
                                    val root = File(applicationContext.filesDir, "restore_staging").canonicalFile
                                    val stage = File(root, restoreId).canonicalFile
                                    require(stage.parentFile?.canonicalFile == root && stage.name == restoreId) {
                                        "Đường dẫn staging remote không an toàn"
                                    }
                                    // A remote restore has no ZIP/photo staging directory. Store a
                                    // canonical cleanup path only; do not create empty disk folders.
                                    val remoteJournal = RestoreJournal(
                                        restoreId = restoreId,
                                        ownerId = ownerId,
                                        manifestSha256 = digest.lowercase(),
                                        status = "REMOTE_COMMIT_CONFIRMED",
                                        stagedDirectory = stage.absolutePath,
                                        partCount = partCount,
                                        movementCount = movementCount,
                                        snapshotCursor = eventId,
                                        sourceType = "REMOTE_FEED",
                                        partChunkCount = partChunks,
                                        movementChunkCount = movementChunks
                                    )
                                    RestoreSnapshotGate.mutex.withLock {
                                        val latest = dao.restoreJournal(ownerId, restoreId)
                                        if (latest?.status == "COMPLETE") {
                                            require(latest.manifestSha256.equals(digest, ignoreCase = true) &&
                                                latest.snapshotCursor == eventId && latest.partCount == partCount &&
                                                latest.movementCount == movementCount) {
                                                "Journal COMPLETE không khớp RESTORE_COMMIT"
                                            }
                                        } else {
                                            if (latest == null && !dao.beginRemoteRestoreIfSafe(remoteJournal)) {
                                                blockedByLocalChanges = true
                                            }
                                            if (!blockedByLocalChanges) {
                                                val current = dao.restoreJournal(ownerId, restoreId)
                                                require(current?.ownerId == ownerId) { "Không tìm thấy journal snapshot của chủ kho" }
                                                require(current.sourceType == "REMOTE_FEED" &&
                                                    current.manifestSha256.equals(digest, ignoreCase = true) &&
                                                    current.snapshotCursor == eventId && current.partCount == partCount &&
                                                    current.movementCount == movementCount) {
                                                    "Journal snapshot thay đổi trong lúc đồng bộ RESTORE_COMMIT"
                                                }
                                                when (current.status) {
                                                    "REMOTE_COMMIT_CONFIRMED", "SNAPSHOT_DOWNLOADING" ->
                                                        RestoreCloudSnapshotReader(applicationContext, dao).download(ownerId, restoreId)
                                                    "SNAPSHOT_DOWNLOADED", "COMPLETE" -> Unit
                                                    else -> error("Trạng thái snapshot không thể tiếp tục: ${current.status}")
                                                }
                                                val beforeApply = dao.restoreJournal(ownerId, restoreId)
                                                if (beforeApply?.status == "SNAPSHOT_DOWNLOADED") {
                                                    if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()
                                                    RestoreCloudSnapshotApplier(applicationContext, dao).apply(ownerId, restoreId)
                                                } else if (beforeApply?.status != "COMPLETE") {
                                                    blockedByLocalChanges = true
                                                }
                                            }
                                        }
                                    }
                                    if (blockedByLocalChanges) break
                                }
                            }
                            // For the owner feed, this line is reached only after the
                            // exact snapshot has been applied locally or a matching
                            // COMPLETE journal proves this device already applied it.
                            // For Admin-global feed, it only advances that separate cursor.
                        }
                        else -> error("Loại event không hỗ trợ: $entityType")
                    }
                    cursor = eventId
                    if (!dao.savePulledCursorIfUnlocked(ownerId, SyncCursor(cursorKey, cursor))) {
                        blockedByLocalChanges = true
                        break
                    }
                }
                if (isAdmin && !hasActiveAdminProfile(ownerId)) {
                    // If the role changed while applying a fetched page, remove any
                    // foreign history from that page before this worker exits.
                    dao.clearForeignMovementCache(ownerId)
                    dao.clearSyncCursor(cursorKey)
                    return Result.success()
                }
                if (blockedByLocalChanges) break
                if (events.size < PAGE_SIZE) break
                // A full page at the run cap may have more events behind it.
                // Retry from the last durably applied cursor instead of reporting
                // success and waiting only for the next periodic 15-minute run.
                if (pages == MAX_PAGES_PER_RUN) pageLimitReached = true
            }
            if (isAdmin && !hasActiveAdminProfile(ownerId)) {
                dao.clearForeignMovementCache(ownerId)
                dao.clearSyncCursor(cursorKey)
                return Result.success()
            }
            when {
                blockedByLocalChanges -> Result.retry()
                pageLimitReached -> Result.retry()
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun hasActiveAdminProfile(ownerId: String): Boolean {
        if (client.auth.currentUserOrNull()?.id != ownerId) return false
        val profile = client.from("profiles").select {
            filter { eq("id", ownerId) }
        }.decodeSingle<UserProfile>()
        return profile.isActive && profile.role.equals("admin", ignoreCase = true) &&
            client.auth.currentUserOrNull()?.id == ownerId
    }

    companion object {
        const val KEY_OWNER_ID = "owner_id"
        const val KEY_IS_ADMIN = "is_admin"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES_PER_RUN = 5
    }
}

private fun kotlinx.serialization.json.JsonObject.toLocalPart(ownerId: String): Part {
    fun string(key: String, default: String = ""): String = this[key]?.jsonPrimitive?.contentOrNull ?: default
    fun int(key: String, default: Int = 0): Int = this[key]?.jsonPrimitive?.intOrNull ?: default
    fun long(key: String, default: Long = 0L): Long {
        val raw = string(key)
        return raw.toLongOrNull() ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(default)
    }
    val cloudId = string("id")
    require(cloudId.isNotBlank()) { "Snapshot PART thiếu UUID" }
    return Part(
        code = string("code"), name = string("name"), quantity = int("quantity"),
        minQuantity = int("min_quantity"), unit = string("unit", "cái"),
        // Giữ nguyên object path của Supabase Storage; không tải ảnh về thiết bị khi pull.
        photoPath = string("photo_path"), note = string("note"), updatedAt = long("updated_at", System.currentTimeMillis()),
        trackingMode = string("tracking_mode", "SINGLE"), ownerId = ownerId,
        syncId = cloudId, serverVersion = long("server_version", 0L)
    )
}

private fun kotlinx.serialization.json.JsonObject.toLocalMovement(ownerId: String, allowForeignOwner: Boolean = false): Movement {
    fun requiredString(key: String): String = this[key]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: error("Snapshot MOVEMENT thiếu $key")
    fun requiredInt(key: String): Int = this[key]?.jsonPrimitive?.intOrNull
        ?: error("Snapshot MOVEMENT có $key không hợp lệ")

    val cloudId = requiredString("id")
    val canonicalId = runCatching { java.util.UUID.fromString(cloudId).toString() }
        .getOrElse { error("Snapshot MOVEMENT có UUID không hợp lệ") }
    require(canonicalId == cloudId.lowercase()) { "Snapshot MOVEMENT có UUID không chuẩn" }
    val payloadOwnerId = this["owner_id"]?.jsonPrimitive?.contentOrNull
    val movementOwnerId = MovementOwnerResolver.resolve(ownerId, payloadOwnerId, allowForeignOwner)
    val type = requiredString("type")
    val amount = requiredInt("amount")
    val beforeQty = requiredInt("before_qty")
    val afterQty = requiredInt("after_qty")
    require(type in setOf("NHẬP", "XUẤT", "KIỂM KÊ", "ĐẦU KỲ")) {
        "Snapshot MOVEMENT có loại giao dịch không hỗ trợ"
    }
    require(amount >= 0 && beforeQty >= 0 && afterQty >= 0 && (type == "KIỂM KÊ" || amount > 0)) {
        "Snapshot MOVEMENT có số lượng không hợp lệ"
    }
    when (type) {
        "NHẬP" -> require(afterQty.toLong() == beforeQty.toLong() + amount.toLong()) {
            "Snapshot MOVEMENT nhập kho không khớp số lượng"
        }
        "XUẤT" -> require(beforeQty >= amount && afterQty.toLong() == beforeQty.toLong() - amount.toLong()) {
            "Snapshot MOVEMENT xuất kho không khớp số lượng"
        }
        "KIỂM KÊ" -> require(afterQty == amount) { "Snapshot MOVEMENT kiểm kê không khớp số lượng" }
        "ĐẦU KỲ" -> require(beforeQty == 0 && afterQty == amount) { "Snapshot MOVEMENT đầu kỳ không hợp lệ" }
    }
    val timeText = this["occurred_at"]?.jsonPrimitive?.contentOrNull
        ?: this["created_at"]?.jsonPrimitive?.contentOrNull
        ?: error("Snapshot MOVEMENT thiếu thời gian")
    val time = runCatching { Instant.parse(timeText).toEpochMilli() }
        .getOrElse { error("Snapshot MOVEMENT có thời gian không hợp lệ") }
    require(time > 0L) { "Snapshot MOVEMENT có thời gian không hợp lệ" }
    return Movement(
        code = requiredString("code"), partName = requiredString("part_name"), type = type,
        amount = amount, beforeQty = beforeQty, afterQty = afterQty,
        reason = this["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        machineOrStore = this["machine_or_store"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        time = time, ownerId = movementOwnerId,
        ownerName = this["owner_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), syncId = canonicalId
    )
}
