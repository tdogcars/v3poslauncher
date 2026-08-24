package com.flo.v3poslauncher.util

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted, iterated SHA-256 for the admin PIN.
 *
 * Honest scope note: a 4-digit PIN has 10,000 possibilities, so no hash can make it resistant
 * to offline brute force by someone who already has root on the device. The purpose of hashing
 * is only that the PIN is never at rest in plaintext and never appears in a log or backup.
 * The real protection is the attempt lockout in PinActivity.
 */
object PinHasher {
    private const val ITERATIONS = 20_000
    private val random = SecureRandom()

    /** Returns (saltHex, hashHex). */
    fun hashNew(pin: String): Pair<String, String> {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val saltHex = Hex.encode(salt)
        return saltHex to Hex.encode(hash(pin, salt))
    }

    fun verify(pin: String, saltHex: String, expectedHex: String): Boolean {
        val salt = Hex.decode(saltHex) ?: return false
        val expected = Hex.decode(expectedHex) ?: return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        var h = md.run { update(salt); update(pin.toByteArray(Charsets.UTF_8)); digest() }
        repeat(ITERATIONS - 1) {
            md.reset(); md.update(salt); md.update(h); h = md.digest()
        }
        return h
    }
}
