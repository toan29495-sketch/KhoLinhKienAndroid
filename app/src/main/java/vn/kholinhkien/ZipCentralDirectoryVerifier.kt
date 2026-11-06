package vn.kholinhkien

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Strict structural validation for archives produced by this app's ZIP v2 writer. */
object ZipCentralDirectoryVerifier {
    private const val EOCD = 0x06054b50L
    private const val CENTRAL = 0x02014b50L
    private const val LOCAL = 0x04034b50L
    private const val DESCRIPTOR = 0x08074b50L
    private const val MAX_ENTRIES = 10002
    private const val MAX_ARCHIVE_BYTES = 320L * 1024L * 1024L
    private const val UTF8_FLAG = 0x0800
    private const val DESCRIPTOR_FLAG = 0x0008

    fun verify(channel: FileChannel): Set<String> {
        val size = channel.size()
        require(size in 22L..MAX_ARCHIVE_BYTES) { "Kích thước tệp ZIP không hợp lệ" }
        val tailSize = minOf(size, 22L + 65535L).toInt()
        val tailStart = size - tailSize
        val tail = read(channel, tailStart, tailSize).order(ByteOrder.LITTLE_ENDIAN)
        var eocdInTail = -1
        for (index in tail.limit() - 22 downTo 0) {
            if (u32(tail, index) == EOCD) {
                val commentLength = u16(tail, index + 20)
                if (index + 22 + commentLength == tail.limit()) {
                    eocdInTail = index
                    break
                }
            }
        }
        require(eocdInTail >= 0) { "ZIP thiếu hoặc sai cấu trúc EOCD" }
        val eocdOffset = tailStart + eocdInTail
        val diskNumber = u16(tail, eocdInTail + 4)
        val centralDisk = u16(tail, eocdInTail + 6)
        val entriesOnDisk = u16(tail, eocdInTail + 8)
        val entryCount = u16(tail, eocdInTail + 10)
        val centralSize = u32(tail, eocdInTail + 12)
        val centralOffset = u32(tail, eocdInTail + 16)
        val commentLength = u16(tail, eocdInTail + 20)
        require(diskNumber == 0 && centralDisk == 0 && entriesOnDisk == entryCount) {
            "ZIP nhiều phân vùng không được hỗ trợ"
        }
        require(entryCount in 1..MAX_ENTRIES && entryCount != 0xffff && centralSize != 0xffffffffL && centralOffset != 0xffffffffL) {
            "ZIP có số mục hoặc ZIP64 không được hỗ trợ"
        }
        require(commentLength == 0 && centralOffset + centralSize == eocdOffset) {
            "Vùng Central Directory/EOCD không liền mạch hoặc có dữ liệu nối thêm"
        }

        var cursor = centralOffset
        val names = linkedSetOf<String>()
        val ranges = mutableListOf<LongRange>()
        repeat(entryCount) {
            val header = read(channel, cursor, 46).order(ByteOrder.LITTLE_ENDIAN)
            require(u32(header, 0) == CENTRAL) { "Central Directory có header không hợp lệ" }
            val flags = u16(header, 8)
            val method = u16(header, 10)
            val crc = u32(header, 16)
            val compressed = u32(header, 20)
            val uncompressed = u32(header, 24)
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val entryCommentLength = u16(header, 32)
            val diskStart = u16(header, 34)
            val internalAttributes = u16(header, 36)
            val externalAttributes = u32(header, 38)
            val localOffset = u32(header, 42)
            require(flags and (UTF8_FLAG or DESCRIPTOR_FLAG).inv() == 0 && flags and UTF8_FLAG != 0) {
                "ZIP có cờ mã hóa hoặc cờ nén không được hỗ trợ"
            }
            require(method == 8 && diskStart == 0 && extraLength == 0 && entryCommentLength == 0 &&
                internalAttributes == 0 && externalAttributes == 0L) {
                "Mục ZIP có phương thức, thuộc tính hoặc trường mở rộng không được hỗ trợ"
            }
            require(compressed != 0xffffffffL && uncompressed != 0xffffffffL && localOffset != 0xffffffffL) {
                "Mục ZIP64 không được hỗ trợ"
            }
            require(nameLength in 1..1024 && localOffset < centralOffset) { "Tên hoặc vị trí mục ZIP không hợp lệ" }
            val nameBytes = read(channel, cursor + 46, nameLength)
            val name = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(nameBytes).toString()
            require(isSafeName(name)) { "Tên mục ZIP không an toàn" }
            require(names.add(name)) { "Central Directory chứa tên mục trùng lặp" }

            val local = read(channel, localOffset, 30).order(ByteOrder.LITTLE_ENDIAN)
            require(u32(local, 0) == LOCAL) { "Local File Header không hợp lệ: $name" }
            val localFlags = u16(local, 6)
            val localMethod = u16(local, 8)
            val localNameLength = u16(local, 26)
            val localExtraLength = u16(local, 28)
            require(localFlags == flags && localMethod == method && localExtraLength == 0 && localNameLength == nameLength) {
                "Local File Header không khớp Central Directory: $name"
            }
            val localName = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(read(channel, localOffset + 30, localNameLength)).toString()
            require(localName == name) { "Tên Local File Header không khớp: $name" }
            if (flags and DESCRIPTOR_FLAG == 0) {
                require(u32(local, 14) == crc && u32(local, 18) == compressed && u32(local, 22) == uncompressed) {
                    "Kích thước/CRC trong Local File Header không khớp: $name"
                }
            } else {
                require(u32(local, 14) == 0L && u32(local, 18) == 0L && u32(local, 22) == 0L) {
                    "Local File Header có kích thước bất thường: $name"
                }
            }
            val dataStart = localOffset + 30L + localNameLength + localExtraLength
            val dataEnd = dataStart + compressed
            require(dataEnd <= centralOffset && dataEnd >= dataStart) { "Dữ liệu ZIP vượt vùng Central Directory: $name" }
            var rangeEnd = dataEnd
            if (flags and DESCRIPTOR_FLAG != 0) {
                val descriptor = read(channel, dataEnd, 16).order(ByteOrder.LITTLE_ENDIAN)
                require(u32(descriptor, 0) == DESCRIPTOR && u32(descriptor, 4) == crc &&
                    u32(descriptor, 8) == compressed && u32(descriptor, 12) == uncompressed) {
                    "Data Descriptor không khớp: $name"
                }
                rangeEnd += 16L
            }
            require(rangeEnd <= centralOffset) { "Data Descriptor vượt vùng Central Directory: $name" }
            ranges += localOffset..(rangeEnd - 1L)
            cursor += 46L + nameLength + extraLength + entryCommentLength
        }
        require(cursor == centralOffset + centralSize) { "Kích thước Central Directory không khớp" }
        val ordered = ranges.sortedBy { it.first }
        require(ordered.first().first == 0L) { "ZIP có dữ liệu mở đầu không được hỗ trợ" }
        for (index in 1 until ordered.size) {
            require(ordered[index - 1].last + 1L == ordered[index].first) {
                "Các mục ZIP bị chồng lấn hoặc có dữ liệu chen giữa"
            }
        }
        require(ordered.last().last + 1L == centralOffset) {
            "ZIP có dữ liệu thừa trước Central Directory"
        }
        return names
    }

    private fun isSafeName(name: String): Boolean = name.isNotBlank() &&
        !name.startsWith("/") && !name.contains('\\') &&
        name.split('/').none { it.isBlank() || it == ".." } &&
        (name == "manifest.json" || name == "parts.jsonl" || name == "movements.jsonl" ||
            (name.startsWith("photos/") &&
                name.removePrefix("photos/").matches(Regex("[A-Za-z0-9._-]+"))))

    private fun read(channel: FileChannel, position: Long, length: Int): ByteBuffer {
        require(position >= 0L && length >= 0 && position + length <= channel.size()) { "ZIP bị cắt cụt" }
        val buffer = ByteBuffer.allocate(length)
        var offset = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, offset)
            require(count > 0) { "ZIP bị cắt cụt" }
            offset += count.toLong()
        }
        buffer.flip()
        return buffer
    }

    private fun u16(buffer: ByteBuffer, offset: Int): Int =
        (buffer.get(offset).toInt() and 0xff) or ((buffer.get(offset + 1).toInt() and 0xff) shl 8)

    private fun u32(buffer: ByteBuffer, offset: Int): Long =
        (u16(buffer, offset).toLong() or (u16(buffer, offset + 2).toLong() shl 16))
}
