package vn.kholinhkien

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Backup read-back validation and restore-file metadata preview. */
data class BackupArchivePreview(
    val fileName: String,
    val ownerId: String,
    val username: String,
    val createdAt: Long,
    val partCount: Int,
    val movementCount: Int,
    val version: Int,
    val backupKind: String = "MANUAL",
    val snapshotCursor: Long? = null
)

object BackupArchiveInspector {
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 300L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 100L * 1024L * 1024L
    private const val MAX_PHOTO_BYTES = 20L * 1024L * 1024L
    private const val MAX_ENTRIES = 10002

    /** Re-reads a closed backup and verifies its payload before it is made visible. */
    fun verify(input: InputStream, expectedOwnerId: String): Set<String> {
        require(expectedOwnerId.isNotBlank()) { "Không xác định được tài khoản đang đăng nhập" }
        val seen = linkedSetOf<String>()
        val digests = linkedMapOf<String, String>()
        var totalBytes = 0L
        var entryCount = 0
        var partsLines = 0
        var movementLines = 0
        var manifestBytes: ByteArray? = null

        ZipInputStream(input.buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entryCount <= MAX_ENTRIES) { "ZIP có quá nhiều mục" }
                val name = entry.name
                require(name.isNotBlank() && !name.startsWith("/") && !name.contains("\\") &&
                    name.split("/").none { it == ".." || it.isBlank() }) {
                    "Tệp ZIP có đường dẫn không an toàn"
                }
                if (entry.isDirectory) {
                    require(name == "photos/") { "Thư mục trong ZIP không hợp lệ" }
                    zip.closeEntry()
                    continue
                }
                require(seen.add(name)) { "ZIP chứa mục bị trùng: $name" }
                val isPhoto = name.startsWith("photos/") &&
                    name.removePrefix("photos/").matches(Regex("[A-Za-z0-9._-]+"))
                require(name == "manifest.json" || name == "parts.jsonl" ||
                    name == "movements.jsonl" || isPhoto) {
                    "Tệp ZIP chứa thành phần không hợp lệ: $name"
                }
                val limit = when {
                    name == "manifest.json" -> MAX_MANIFEST_BYTES
                    isPhoto -> MAX_PHOTO_BYTES
                    else -> MAX_ENTRY_BYTES
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val manifestOutput = if (name == "manifest.json") java.io.ByteArrayOutputStream() else null
                var entryBytes = 0L
                var newlineCount = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count.toLong()
                    totalBytes += count.toLong()
                    require(entryBytes <= limit) { "Mục ZIP quá lớn: $name" }
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Bản sao lưu vượt giới hạn tổng 300 MB" }
                    digest.update(buffer, 0, count)
                    if (name == "parts.jsonl" || name == "movements.jsonl") {
                        for (i in 0 until count) if (buffer[i] == '\n'.code.toByte()) newlineCount++
                    }
                    manifestOutput?.write(buffer, 0, count)
                }
                if (name == "manifest.json") manifestBytes = manifestOutput!!.toByteArray()
                else digests[name] = digest.digest().toHex()
                if (name == "parts.jsonl") partsLines = newlineCount
                if (name == "movements.jsonl") movementLines = newlineCount
                zip.closeEntry()
            }
        }

        require("manifest.json" in seen && "parts.jsonl" in seen && "movements.jsonl" in seen) {
            "ZIP thiếu manifest hoặc dữ liệu linh kiện/lịch sử"
        }
        val manifestRaw = manifestBytes ?: error("ZIP không có manifest.json hợp lệ")
        val manifest = JSONObject(String(manifestRaw, Charsets.UTF_8))
        require(manifest.optInt("version", -1) == 2) { "Phiên bản sao lưu không được hỗ trợ" }
        val ownerId = manifest.optString("ownerId", "")
        require(ownerId.isNotBlank() && ownerId == expectedOwnerId) {
            "Bản sao lưu thuộc KTV/tài khoản khác"
        }
        val backupKind = manifest.optString("backupKind", "MANUAL")
        require(backupKind in setOf("MANUAL", "WEEKLY_AUTO", "PRE_UPDATE")) {
            "Loại bản sao lưu không được hỗ trợ"
        }
        if (backupKind == "WEEKLY_AUTO") {
            require(manifest.has("snapshotCursor") && manifest.optLong("snapshotCursor", -1L) >= 0L) {
                "Backup tuần thiếu cursor snapshot hợp lệ"
            }
        }
        val expectedParts = manifest.optInt("partCount", -1)
        val expectedMovements = manifest.optInt("movementCount", -1)
        require(expectedParts in 0..500_000 && expectedMovements in 0..1_000_000) {
            "Số lượng dữ liệu trong manifest không hợp lệ"
        }
        require(partsLines == expectedParts && movementLines == expectedMovements) {
            "Số lượng dữ liệu trong ZIP không khớp manifest"
        }
        val checksums = manifest.optJSONObject("checksums") ?: error("Manifest thiếu danh sách SHA-256")
        val checksumNames = buildSet {
            val keys = checksums.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(checksumNames == seen.filter { it != "manifest.json" }.toSet()) {
            "Danh sách SHA-256 không khớp chính xác với các thành phần trong ZIP"
        }
        digests.forEach { (name, actual) ->
            val expected = checksums.optString(name, "")
            require(expected.matches(Regex("[a-fA-F0-9]{64}")) && actual.equals(expected, true)) {
                "Dữ liệu bị lỗi hoặc đã thay đổi: $name"
            }
        }
        return seen
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun inspect(context: Context, uri: Uri, expectedOwnerId: String): BackupArchivePreview {
        require(expectedOwnerId.isNotBlank()) { "Không xác định được tài khoản đang đăng nhập" }
        val manifestBytes = context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var found: ByteArray? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entry.name != "../manifest.json" && !entry.name.startsWith("/")) {
                        "Đường dẫn trong ZIP không hợp lệ"
                    }
                    if (entry.name == "manifest.json") {
                        require(!entry.isDirectory) { "Manifest ZIP không hợp lệ" }
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count.toLong()
                            require(total <= MAX_MANIFEST_BYTES) { "Manifest ZIP vượt giới hạn 1 MB" }
                            output.write(buffer, 0, count)
                        }
                        found = output.toByteArray()
                        break
                    }
                    zip.closeEntry()
                }
                found ?: error("ZIP không có manifest.json")
            }
        } ?: error("Không mở được tệp ZIP")

        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val version = manifest.optInt("version", -1)
        require(version == 2) { "Phiên bản sao lưu không được hỗ trợ" }
        val ownerId = manifest.optString("ownerId", "")
        require(ownerId.isNotBlank()) { "Bản sao lưu không có thông tin chủ sở hữu" }
        require(ownerId == expectedOwnerId) {
            "Bản sao lưu thuộc KTV/tài khoản khác. Ứng dụng không cho khôi phục chéo tài khoản."
        }
        val parts = manifest.optInt("partCount", -1)
        val movements = manifest.optInt("movementCount", -1)
        val backupKind = manifest.optString("backupKind", "MANUAL")
        val snapshotCursor = if (manifest.has("snapshotCursor")) manifest.optLong("snapshotCursor", -1L) else null
        require(backupKind in setOf("MANUAL", "WEEKLY_AUTO", "PRE_UPDATE")) {
            "Loại bản sao lưu không được hỗ trợ"
        }
        if (backupKind == "WEEKLY_AUTO") {
            require(snapshotCursor != null && snapshotCursor >= 0L) {
                "Backup tuần thiếu cursor snapshot hợp lệ"
            }
        }
        require(parts in 0..500_000 && movements in 0..1_000_000) {
            "Số lượng dữ liệu trong manifest không hợp lệ"
        }
        return BackupArchivePreview(
            fileName = manifest.optString("fileName", "").ifBlank { uri.lastPathSegment ?: "Tệp ZIP" },
            ownerId = ownerId,
            username = manifest.optString("backupUsername", "KTV (bản sao lưu cũ)"),
            createdAt = manifest.optLong("createdAt", 0L),
            partCount = parts,
            movementCount = movements,
            version = version,
            backupKind = backupKind,
            snapshotCursor = snapshotCursor
        )
    }
}
