package vn.kholinhkien

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Calls the server's atomic snapshot replacement only after every staged chunk
 * has been acknowledged. It never edits local Room inventory. */
internal class RestoreCloudCommitter(
    private val dao: InventoryDao,
    private val client: SupabaseClient = SupabaseClientProvider.client
) {
    data class CommitResult(
        val restoreId: String,
        val manifestSha256: String,
        val snapshotCursor: Long,
        val partCount: Int,
        val movementCount: Int,
        val alreadyCommitted: Boolean
    )

    suspend fun commit(ownerId: String, restoreId: String): CommitResult = mutex.withLock {
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("Cần đăng nhập trước khi xác nhận khôi phục cloud")
        require(userId == ownerId) { "Tài khoản đăng nhập không khớp chủ kho cần khôi phục" }

        val journal = requireNotNull(dao.restoreJournal(ownerId, restoreId)) { "Không tìm thấy journal khôi phục" }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        require(journal.status in setOf("CLOUD_STAGED", "COMMIT_UNKNOWN", "COMMIT_CONFIRMED")) {
            "Chưa gửi đủ dữ liệu staging lên cloud: ${journal.status}"
        }
        require(journal.manifestSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            "Manifest SHA-256 không hợp lệ"
        }

        // A durable confirmation is stronger than a network response: once the
        // cursor and counts are journaled, never regress it to COMMIT_UNKNOWN or
        // make another network call merely because the user resumed the screen.
        if (journal.status == "COMMIT_CONFIRMED") {
            require(journal.snapshotCursor > 0L) {
                "Journal đã xác nhận commit nhưng thiếu snapshot cursor; cần kiểm tra an toàn"
            }
            return@withLock CommitResult(
                restoreId = restoreId,
                manifestSha256 = journal.manifestSha256,
                snapshotCursor = journal.snapshotCursor,
                partCount = journal.partCount,
                movementCount = journal.movementCount,
                alreadyCommitted = true
            )
        }

        // Reissuing the same idempotent RPC resolves the ambiguous case where the
        // server committed but the app lost the response before journaling it.
        // Only CLOUD_STAGED starts a new commit; COMMIT_UNKNOWN replays the exact
        // same restoreId so the server returns its immutable commit ledger result.
        dao.saveRestoreJournal(journal.copy(
            status = "COMMIT_UNKNOWN",
            lastError = "",
            updatedAt = System.currentTimeMillis()
        ))
        val raw = try {
            client.postgrest.rpc(
                "commit_inventory_restore",
                parameters = buildJsonObject { put("p_restore_id", restoreId) }
            )
        } catch (cancelled: CancellationException) {
            // COMMIT_UNKNOWN was durably written before the RPC. Keep it unchanged:
            // the server may have committed even though this client stopped waiting.
            throw cancelled
        } catch (error: Exception) {
            dao.saveRestoreJournal(journal.copy(
                status = "COMMIT_UNKNOWN",
                lastError = (error.message ?: error::class.java.simpleName).take(1000),
                updatedAt = System.currentTimeMillis()
            ))
            throw error
        }
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi sau yêu cầu commit; không áp dụng kết quả vào phiên khác"
        }
        val result = Json.parseToJsonElement(raw.toString()).jsonObject
        val status = result["status"]?.jsonPrimitive?.contentOrNull
        val returnedRestoreId = result["restore_id"]?.jsonPrimitive?.contentOrNull
        val digest = result["manifest_sha256"]?.jsonPrimitive?.contentOrNull
        val cursor = result["snapshot_cursor"]?.jsonPrimitive?.longOrNull
        val parts = result["part_count"]?.jsonPrimitive?.intOrNull
        val movements = result["movement_count"]?.jsonPrimitive?.intOrNull

        require(status == "committed" || status == "already_committed") {
            "Máy chủ trả trạng thái commit không hợp lệ: ${status ?: "missing"}"
        }
        require(returnedRestoreId == restoreId) { "Máy chủ trả sai restoreId" }
        require(digest.equals(journal.manifestSha256, ignoreCase = true)) {
            "Manifest SHA-256 trên máy chủ không khớp bản ZIP"
        }
        require(cursor != null && cursor > 0L) { "Máy chủ không trả snapshot cursor hợp lệ" }
        require(parts == journal.partCount && movements == journal.movementCount) {
            "Số lượng snapshot trên máy chủ không khớp journal cục bộ"
        }

        dao.saveRestoreJournal(journal.copy(
            status = "COMMIT_CONFIRMED",
            snapshotCursor = cursor,
            lastError = "",
            updatedAt = System.currentTimeMillis()
        ))
        CommitResult(
            restoreId = restoreId,
            manifestSha256 = digest!!,
            snapshotCursor = cursor,
            partCount = parts!!,
            movementCount = movements!!,
            alreadyCommitted = status == "already_committed"
        )
    }

    private companion object {
        val mutex = Mutex()
    }
}
