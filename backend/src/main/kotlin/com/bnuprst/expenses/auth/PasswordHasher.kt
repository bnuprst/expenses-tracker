package com.bnuprst.expenses.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Argon2id hashes stored as `argon2id$<iterations>$<memoryKb>$<salt b64>$<hash b64>`. */
object PasswordHasher {
    private const val ITERATIONS = 3
    private const val MEMORY_KB = 64 * 1024
    private const val PARALLELISM = 1
    private const val HASH_LEN = 32
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt, ITERATIONS, MEMORY_KB)
        return "argon2id\$$ITERATIONS\$$MEMORY_KB\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 5 || parts[0] != "argon2id") return false
        val salt = Base64.getDecoder().decode(parts[3])
        val expected = Base64.getDecoder().decode(parts[4])
        val actual = derive(password, salt, parts[1].toInt(), parts[2].toInt())
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int, memoryKb: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(PARALLELISM)
            .build()
        return ByteArray(HASH_LEN).also {
            Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), it)
        }
    }
}
