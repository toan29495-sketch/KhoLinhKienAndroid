package vn.kholinhkien

import java.io.File
import java.io.OutputStream

/**
 * Bounds a weekly ZIP write by the server archive limit and preserves free space.
 * If storage runs low, the caller deletes the incomplete PREPARING archive while
 * retaining its journal identity; READY/UPLOADING archives are never passed here.
 */
internal class WeeklyBackupGuardedOutputStream(
    private val delegate: OutputStream,
    private val stagingDirectory: File,
    private val reserveBytes: Long,
    private val maxBytes: Long,
    private val checkActive: () -> Unit = {}
) : OutputStream() {
    private var writtenBytes = 0L

    init {
        require(reserveBytes >= 0L && maxBytes > 0L)
    }

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
        writtenBytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return
        ensureCapacity(length.toLong())
        delegate.write(buffer, offset, length)
        writtenBytes += length.toLong()
    }

    private fun ensureCapacity(nextBytes: Long) {
        checkActive()
        if (nextBytes > maxBytes - writtenBytes) {
            throw WeeklyBackupStorageException("ZIP backup vượt giới hạn 256 MiB; không thể hoàn tất an toàn")
        }
        val free = stagingDirectory.usableSpace
        if (free < nextBytes + reserveBytes) {
            throw WeeklyBackupStorageException(
                "Bộ nhớ thiết bị sắp hết khi tạo backup; cần giải phóng thêm dung lượng rồi thử lại"
            )
        }
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
