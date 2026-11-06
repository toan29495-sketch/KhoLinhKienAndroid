import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import vn.kholinhkien.ZipCentralDirectoryVerifier

private fun makeZip(path: String, includeEmpty: Boolean = false) {
    ZipOutputStream(FileOutputStream(path)).use { zip ->
        listOf("parts.jsonl", "movements.jsonl", "manifest.json", "photos/1.jpg").forEach { name ->
            zip.putNextEntry(ZipEntry(name))
            zip.write("payload-$name\n".toByteArray())
            zip.closeEntry()
        }
        if (includeEmpty) {
            zip.putNextEntry(ZipEntry("photos/2.jpg"))
            zip.closeEntry()
        }
    }
}

private fun expectAccept(label: String, file: File, expected: Int) {
    val names = RandomAccessFile(file, "r").use { ZipCentralDirectoryVerifier.verify(it.channel) }
    check(names.size == expected) { "$label: expected $expected entries, got ${names.size}" }
    println("PASS accepts $label")
}

private fun expectReject(label: String, file: File) {
    val accepted = runCatching {
        RandomAccessFile(file, "r").use { ZipCentralDirectoryVerifier.verify(it.channel) }
    }.isSuccess
    check(!accepted) { "Unexpected acceptance: $label" }
    println("PASS rejects $label")
}

private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    for (i in 0..size - needle.size) if (needle.indices.all { this[i + it] == needle[it] }) return i
    return -1
}

fun main() {
    val base = File("/tmp/step123_valid.zip")
    makeZip(base.absolutePath)
    val bytes = base.readBytes()
    val eocd = bytes.indexOfSequence(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
    val central = bytes.indexOfSequence(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
    val local = bytes.indexOfSequence(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
    val descriptor = bytes.indexOfSequence(byteArrayOf(0x50, 0x4b, 0x07, 0x08))
    check(eocd >= 0 && central >= 0 && local >= 0 && descriptor >= 0)
    expectAccept("normal app-shaped DEFLATED archive", base, 4)

    val empty = File("/tmp/step123_empty_entry.zip")
    makeZip(empty.absolutePath, includeEmpty = true)
    expectAccept("zero-byte photo entry", empty, 5)

    fun corrupted(label: String, offset: Int, value: Int = (bytes[offset].toInt() xor 1) and 0xff) {
        val target = File("/tmp/step123_bad_${label.replace(' ', '_')}.zip")
        base.copyTo(target, overwrite = true)
        RandomAccessFile(target, "rw").use { it.seek(offset.toLong()); it.write(value) }
        expectReject(label, target)
    }

    corrupted("EOCD signature", eocd)
    corrupted("EOCD comment length", eocd + 20, 1)
    corrupted("multi-disk marker", eocd + 4, 1)
    corrupted("central CRC mismatch", central + 16)
    corrupted("unsupported compression method", central + 10, 0)
    corrupted("encrypted entry flag", central + 8, 1)
    corrupted("local filename mismatch", local + 30)
    corrupted("local CRC field mismatch", local + 14, 1)
    corrupted("data descriptor CRC mismatch", descriptor + 4)
    corrupted("data descriptor size mismatch", descriptor + 8)
    corrupted("central size mismatch", eocd + 12)
    corrupted("central filename length", central + 28, 0)

    val truncated = File("/tmp/step123_truncated.zip")
    truncated.writeBytes(bytes.copyOf(bytes.size - 1))
    expectReject("truncated final byte", truncated)

    println("STEP123_JVM_RESULT=15/15")
}
