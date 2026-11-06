package vn.kholinhkien

import java.security.MessageDigest

/** Small JVM-only integrity helper shared by photo upload verification paths. */
internal object PhotoIntegrity {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun matches(expectedBytes: Int, expectedSha256: String, actual: ByteArray): Boolean =
        expectedBytes > 0 && actual.size == expectedBytes &&
            expectedSha256.matches(Regex("[a-f0-9]{64}")) &&
            sha256(actual) == expectedSha256
}
