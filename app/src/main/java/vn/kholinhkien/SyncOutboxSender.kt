package vn.kholinhkien

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sends local operations in enqueue order. A successful RPC response is the only
 * condition under which an outbox row is marked SYNCED. */
class SyncOutboxSender(
    private val dao: InventoryDao,
    private val appFilesDirectory: File,
    private val client: io.github.jan.supabase.SupabaseClient = SupabaseClientProvider.client
) {
    suspend fun sendPending(ownerId: String): SyncSendResult = processMutex.withLock {
        val authenticatedUserId = client.auth.currentUserOrNull()?.id
            ?: return@withLock SyncSendResult.NoSession
        if (authenticatedUserId != ownerId) return@withLock SyncSendResult.WrongAccount
        // Do not push local mutations while a snapshot restore owns this account.
        // The restore transaction checks for any unsent rows before replacing data.
        if (dao.activeRestore(ownerId) != null) return@withLock SyncSendResult.Partial(0, "Đang khôi phục snapshot; tạm dừng đồng bộ gửi")

        // Recover only claims with a known, expired lease. Legacy claimStartedAt=0
        // rows remain IN_FLIGHT for explicit reconciliation; never steal them blindly.
        dao.recoverExpiredClaims(ownerId, System.currentTimeMillis() - CLAIM_LEASE_MS)
        val pending = dao.pendingOutbox(ownerId)
        var sent = 0
        for (operation in pending) {
            // WorkManager can outlive the UI session. Re-check before claiming each row
            // so a worker queued for a previous account cannot continue under a new one.
            requireActiveOwner(ownerId)
            if (dao.activeRestore(ownerId) != null) {
                return@withLock SyncSendResult.Partial(sent, "Đang khôi phục snapshot; tạm dừng đồng bộ gửi")
            }
            // Freeze the concurrency token and claim the exact row in one Room transaction.
            val claimed = dao.claimOutboxForSend(operation.operationId)
                ?: return@withLock SyncSendResult.Partial(sent, "Không thể nhận giao dịch đầu hàng đợi; đã dừng để giữ đúng thứ tự", retryable = true)
            try {
                requireActiveOwner(ownerId)
                val originalPayload = Json.parseToJsonElement(claimed.payloadJson)
                val photoUpload = if (operation.operationType == "PART_UPSERT") {
                    uploadPartPhotoIfPresent(ownerId, claimed.entityId, claimed.operationId, originalPayload.jsonObject)
                } else null
                val payload = photoUpload?.payload ?: originalPayload
                // Photo upload is a network boundary too; the account may have changed
                // while Storage was receiving bytes.
                requireActiveOwner(ownerId)
                val rpcResponse = client.postgrest.rpc(
                    "apply_inventory_operation",
                    parameters = buildJsonObject {
                        put("p_operation_id", claimed.operationId)
                        put("p_entity_type", claimed.operationType)
                        put("p_entity_id", claimed.entityId)
                        put("p_payload", payload)
                    }
                )
                // Never acknowledge/apply an RPC response after the shared Supabase
                // client has switched to another account during the request.
                requireActiveOwner(ownerId)
                val responseObject = RestoreRpcJson.objectValue(rpcResponse.data, "apply_inventory_operation")
                // A transport success alone is not enough to acknowledge an outbox row:
                // validate that the cached/applied RPC result belongs to this exact part.
                val responseStatus = responseObject["status"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                check(responseStatus == "applied") {
                    "RPC trả trạng thái không xác nhận giao dịch; giữ giao dịch để đối soát"
                }
                val responseEntityId = responseObject["entity_id"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                check(responseEntityId.equals(claimed.entityId, ignoreCase = true)) {
                    "RPC trả entity_id không khớp giao dịch; giữ giao dịch để đối soát"
                }
                val serverVersion = responseObject["server_version"]?.let { (it as? JsonPrimitive)?.longOrNull }
                val markedSynced = if (claimed.operationType == "PART_UPSERT" || claimed.operationType == "PART_DELETE" || claimed.operationType == "STOCK_CHANGE") {
                    check(serverVersion != null && serverVersion > 0L) {
                        "RPC thiếu server_version hợp lệ; giữ giao dịch để đối soát"
                    }
                    when {
                        claimed.operationType == "PART_DELETE" -> dao.completeDeleteOutbox(claimed.operationId, ownerId, claimed.entityId)
                        photoUpload?.localPath != null && photoUpload.remotePath != null ->
                            dao.completePhotoOutbox(claimed.operationId, ownerId, claimed.entityId, photoUpload.localPath, photoUpload.remotePath, serverVersion)
                        else -> dao.completeOutboxWithServerVersion(claimed.operationId, ownerId, claimed.entityId, serverVersion)
                    }
                } else false
                check(markedSynced) {
                    "Máy chủ đã nhận giao dịch nhưng không cập nhật được trạng thái cục bộ"
                }
                // Chỉ xóa ảnh chờ sau khi upload và RPC nghiệp vụ đều xác nhận thành công.
                photoUpload?.localPath?.let { path ->
                    if (!dao.hasPendingPhotoReference(ownerId, path, claimed.operationId)) {
                        runCatching { File(path).delete() }
                    }
                }
                sent++
            } catch (cancelled: CancellationException) {
                // WorkManager cancellation (for example logout/account switch) is not
                // a business failure. Keep IN_FLIGHT + original operationId/payload;
                // lease recovery will retry idempotently under the same owner later.
                throw cancelled
            } catch (error: Exception) {
                val message = (error.message ?: error::class.java.simpleName).take(1000)
                val permanent = SyncConflictClassifier.isPermanent(message)
                if (permanent) dao.markOutboxBlocked(ownerId, claimed.operationId, message)
                else dao.markOutboxFailed(ownerId, claimed.operationId, message)
                // Stop here: later operations may depend on this one.
                return@withLock SyncSendResult.Partial(sent, message, retryable = !permanent)
            }
        }
        SyncSendResult.Completed(sent)
    }

    private fun requireActiveOwner(ownerId: String) {
        val activeId = client.auth.currentUserOrNull()?.id
        check(activeId == ownerId) {
            "Phiên đăng nhập đã thay đổi trong lúc đồng bộ; dừng để bảo vệ dữ liệu tài khoản"
        }
    }

    private data class PhotoUploadResult(val payload: JsonObject, val localPath: String? = null, val remotePath: String? = null)

    private suspend fun uploadPartPhotoIfPresent(
        ownerId: String,
        partSyncId: String,
        operationId: String,
        source: JsonObject
    ): PhotoUploadResult {
        val localPath = source["photo_path"]?.let {
            (it as? JsonPrimitive)?.contentOrNull
        }.orEmpty()
        if (localPath.isBlank()) return PhotoUploadResult(source)

        val file = File(localPath)
        // photo_path tương đối là đường dẫn object cloud, không phải file Android.
        if (!file.isAbsolute) return PhotoUploadResult(source)
        require(Regex("^[0-9a-fA-F-]{36}$").matches(ownerId)) { "Mã chủ kho không hợp lệ khi tải ảnh" }
        val canonicalFile = File(file.canonicalPath)
        val ownerPendingDir = File(File(appFilesDirectory, "pending_part_photos").canonicalPath, ownerId).canonicalPath
        val legacyPendingDir = File(File(appFilesDirectory, "pending_part_photos").canonicalPath).canonicalPath
        val isOwnerScoped = canonicalFile.parentFile?.canonicalPath == ownerPendingDir
        // Legacy files are shared-path artifacts from old releases. They may be
        // retried only when Room proves exclusive ownership across parts and outbox.
        val isLegacyPending = canonicalFile.parentFile?.canonicalPath == legacyPendingDir && canonicalFile.name.startsWith("pending_")
        require(isOwnerScoped || isLegacyPending) {
            "Ảnh chờ đồng bộ không nằm trong thư mục riêng của chủ kho"
        }
        if (isLegacyPending) {
            require(dao.isLocalPhotoReferencedOnlyByOwner(ownerId, canonicalFile.path)) {
                "Ảnh cũ chưa thể xác định an toàn chủ sở hữu; giữ nguyên để tránh lộ ảnh giữa các KTV"
            }
        }
        require(file.isFile) { "Không tìm thấy ảnh chờ đồng bộ trên thiết bị: $localPath" }
        require(file.length() in 1..20L * 1024L * 1024L) { "Ảnh linh kiện vượt giới hạn 20 MB hoặc bị rỗng" }
        val extension = file.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val remotePath = "$ownerId/$partSyncId/$operationId.$extension"
        val mimeType = when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        requireActiveOwner(ownerId)
        // Read once so the bytes uploaded and the expected digest describe the same
        // immutable in-memory snapshot, even if the pending file changes concurrently.
        val photoBytes = file.readBytes()
        require(photoBytes.size in 1..20 * 1024 * 1024) {
            "Ảnh linh kiện đã thay đổi kích thước hoặc vượt giới hạn 20 MB"
        }
        val expectedDigest = PhotoIntegrity.sha256(photoBytes)
        // Hashing up to 20 MiB may take long enough for the shared auth session
        // to change; fence the account again at the exact Storage boundary.
        requireActiveOwner(ownerId)
        client.storage.from("part-images").upload(remotePath, photoBytes) {
            upsert = true
            contentType = ContentType.parse(mimeType)
        }
        // Never publish a photo_path to the parts table based only on the upload
        // response. Read the private object back with the active authenticated session.
        requireActiveOwner(ownerId)
        val storedBytes = client.storage.from("part-images").downloadAuthenticated(remotePath)
        requireActiveOwner(ownerId)
        check(PhotoIntegrity.matches(photoBytes.size, expectedDigest, storedBytes)) {
            "Ảnh trên Storage chưa khớp dữ liệu gốc; giữ ảnh chờ đồng bộ để thử lại"
        }
        val cloudPayload = buildJsonObject {
            source.forEach { (key, value) -> if (key != "photo_path") put(key, value) }
            put("photo_path", remotePath)
        }
        return PhotoUploadResult(cloudPayload, localPath, remotePath)
    }

    private companion object {
        // Prevent periodic and one-shot workers in this app process from
        // resetting or claiming the same IN_FLIGHT row concurrently.
        val processMutex = Mutex()
        const val CLAIM_LEASE_MS = 30L * 60L * 1000L
    }
}

sealed class SyncSendResult {
    data class Completed(val sent: Int) : SyncSendResult()
    data class Partial(val sent: Int, val error: String, val retryable: Boolean = true) : SyncSendResult()
    data object NoSession : SyncSendResult()
    data object WrongAccount : SyncSendResult()
}


object SyncConflictClassifier {
    private val permanentTokens = listOf(
        "PART_VERSION_CONFLICT", "PART_DELETED", "PART_VERSION_REQUIRED",
        "PART_NOT_OWNED", "PART_NOT_FOUND", "OPERATION_ID_CONFLICT",
        "OPERATION_PAYLOAD_CONFLICT", "INVALID_PART_PAYLOAD", "INVALID_STOCK_CHANGE"
    )
    fun isPermanent(raw: String): Boolean = permanentTokens.any { raw.contains(it, ignoreCase = true) }
}
