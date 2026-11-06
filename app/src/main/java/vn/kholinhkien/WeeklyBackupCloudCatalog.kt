package vn.kholinhkien

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * Read-only, authenticated catalog for backups that survive app uninstall.
 * The owner-scoped SQL RPC is deployed by Step187. Callers must still surface
 * unavailable-RPC/auth/network errors distinctly from an empty catalog. This
 * class remains read-only and does not download, restore, delete, or mutate Cloud.
 */
internal class WeeklyBackupCloudCatalog(
    private val client: SupabaseClient = SupabaseClientProvider.client
) {
    data class Entry(
        val backupId: String,
        val backupKind: String,
        val status: Status,
        val storagePath: String,
        val snapshotCursor: Long,
        val archiveSizeBytes: Long,
        val sha256: String,
        val partCount: Int,
        val movementCount: Int,
        val createdAt: String,
        val verifiedAt: String?,
        val deletedAt: String?
    ) {
        /** Only this state may be offered to a later verified-download restore flow. */
        val isRestoreCandidate: Boolean
            get() = status == Status.VERIFIED && verifiedAt != null && deletedAt == null
    }

    enum class Status { PENDING, VERIFIED, FAILED, DELETED }

    suspend fun listForOwner(ownerId: String): List<Entry> {
        requireCanonicalUuid(ownerId, "ownerId")
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đăng nhập không khớp chủ sở hữu backup"
        }
        val raw = client.postgrest.rpc("list_my_inventory_backups")
        // Parse the complete response atomically. A malformed row must not result
        // in a partial catalog that could hide a newer or conflicting backup.
        val rows = Json.parseToJsonElement(raw.toString()).jsonArray.map { parseEntry(it.jsonObject, ownerId) }
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi trong lúc tải danh sách backup"
        }
        require(rows.map { it.backupId }.toSet().size == rows.size) {
            "Danh sách backup Cloud có backupId trùng lặp; từ chối sử dụng"
        }
        return rows.sortedWith(compareByDescending<Entry> { it.createdAt }.thenByDescending { it.backupId })
    }

    private fun parseEntry(row: JsonObject, ownerId: String): Entry {
        fun string(name: String): String = row[name]?.jsonPrimitive?.contentOrNull
            ?: error("Thiếu trường $name trong danh sách backup Cloud")
        fun nullableString(name: String): String? = when (val value = row[name]) {
            null, JsonNull -> null
            else -> value.jsonPrimitive.contentOrNull
                ?: error("Trường $name trong danh sách backup Cloud không hợp lệ")
        }
        fun long(name: String): Long = row[name]?.jsonPrimitive?.longOrNull
            ?: error("Trường $name trong danh sách backup Cloud không hợp lệ")
        fun count(name: String, max: Int): Int = long(name).also {
            require(it in 0..max.toLong()) { "Trường $name vượt giới hạn" }
        }.toInt()

        val id = string("backup_id")
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "backupId Cloud không phải UUID canonical"
        }
        val path = string("storage_path")
        require(path == "$ownerId/$id.zip") { "Đường dẫn backup Cloud không thuộc đúng owner/backupId" }
        val kind = string("backup_kind")
        require(kind in setOf("WEEKLY_AUTO", "MONTHLY_AUTO", "PRE_UPDATE")) {
            "Loại backup Cloud không được hỗ trợ"
        }
        val status = runCatching { Status.valueOf(string("status")) }
            .getOrElse { error("Trạng thái backup Cloud không được hỗ trợ") }
        val size = long("archive_size_bytes")
        require(size in 1..(256L * 1024L * 1024L)) { "Kích thước backup Cloud vượt giới hạn" }
        val sha = string("sha256")
        require(sha.matches(Regex("[a-f0-9]{64}"))) { "SHA-256 backup Cloud không hợp lệ" }
        val cursor = long("snapshot_cursor")
        require(cursor >= 0L) { "snapshot_cursor backup Cloud không hợp lệ" }
        val createdAt = string("created_at")
        require(createdAt.isNotBlank()) { "created_at backup Cloud không hợp lệ" }
        val verifiedAt = nullableString("verified_at")
        val deletedAt = nullableString("deleted_at")
        require(status != Status.VERIFIED || !verifiedAt.isNullOrBlank()) {
            "Backup mang trạng thái VERIFIED nhưng thiếu verified_at"
        }
        require(status != Status.DELETED || !deletedAt.isNullOrBlank()) {
            "Backup mang trạng thái DELETED nhưng thiếu deleted_at"
        }
        return Entry(
            backupId = id,
            backupKind = kind,
            status = status,
            storagePath = path,
            snapshotCursor = cursor,
            archiveSizeBytes = size,
            sha256 = sha,
            partCount = count("part_count", 500_000),
            movementCount = count("movement_count", 1_000_000),
            createdAt = createdAt,
            verifiedAt = verifiedAt,
            deletedAt = deletedAt
        )
    }

    private fun requireCanonicalUuid(value: String, label: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "$label không phải UUID canonical"
        }
    }
}
