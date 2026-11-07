package vn.kholinhkien

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.security.MessageDigest

/**
 * Concrete Storage gateway for ZIP restore, pinned to supabase-kt 3.0.1.
 * Each photo is processed sequentially and bounded by RestorePhotoUploadCoordinator
 * to 20 MiB. Verification downloads the authenticated object and compares SHA-256
 * and byte count before the restore mapper may reference its path.
 *
 * This class deliberately does not delete ZIP staging files or cloud objects.
 * The restore journal/commit coordinator owns cleanup after cloud + Room success.
 */
internal class SupabaseRestorePhotoStorage(
    private val client: SupabaseClient
) {
    suspend fun uploadOnly(remotePath: String, bytes: ByteArray, contentType: String) {
        require(remotePath.isNotBlank()) { "Thiếu đường dẫn Storage" }
        require(bytes.isNotEmpty()) { "Ảnh upload rỗng" }
        val bucket = client.storage.from(BUCKET)
        bucket.upload(remotePath, bytes) {
            upsert = true
            this.contentType = ContentType.parse(contentType)
        }
    }

    suspend fun verify(remotePath: String, expectedSha256: String, expectedBytes: Long): Boolean {
        require(remotePath.isNotBlank()) { "Thiếu đường dẫn Storage để xác minh" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}")) && expectedBytes > 0L) {
            "Thông tin checksum ảnh không hợp lệ"
        }
        // A successful upload response alone is not enough: read the private object
        // back with the current user's authenticated session and compare its bytes.
        val downloaded = client.storage.from(BUCKET).downloadAuthenticated(remotePath)
        return downloaded.size.toLong() == expectedBytes &&
            sha256(downloaded).equals(expectedSha256, ignoreCase = true)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUCKET = "part-images"
    }
}
