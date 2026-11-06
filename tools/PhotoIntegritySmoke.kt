package vn.kholinhkien

fun main() {
    val original = "photo-integrity-step126".toByteArray(Charsets.UTF_8)
    val digest = PhotoIntegrity.sha256(original)
    check(digest == "5fc370ba9f1120f3032cc7591c00fa8c70dc2e9062d5799310ec438d92afd4dc")
    check(PhotoIntegrity.matches(original.size, digest, original))
    check(!PhotoIntegrity.matches(original.size + 1, digest, original))
    check(!PhotoIntegrity.matches(original.size, "0".repeat(64), original))
    check(!PhotoIntegrity.matches(original.size, "bad", original))
    check(!PhotoIntegrity.matches(0, digest, original))
    val tampered = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    check(!PhotoIntegrity.matches(tampered.size, digest, tampered))
    println("PhotoIntegritySmoke: 7/7 PASS")
}
