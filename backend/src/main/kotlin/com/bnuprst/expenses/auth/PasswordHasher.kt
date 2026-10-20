package com.bnuprst.expenses.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id hashes stored as `argon2id$<iterations>$<memoryKb>$<salt b64>$<hash b64>`, so changing the
 * parameters below never breaks existing hashes.
 *
 * OWASP's minimum (19 MiB, t=2) rather than more: login is public, and each hash holds its memory for the
 * whole computation. With [concurrency] capped at 2, a burst of logins costs ~40 MiB instead of
 * exhausting the 512 MB container.
 */
object PasswordHasher {
    private const val ITERATIONS = 2
    private const val MEMORY_KB = 19 * 1024
    private val concurrency = Semaphore(2)
    private const val PARALLELISM = 1
    private const val HASH_LEN = 32
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()

    suspend fun hash(password: String): String = limited { hashBlocking(password) }

    suspend fun verify(password: String, stored: String): Boolean = limited { verifyBlocking(password, stored) }

    private suspend fun <T> limited(block: () -> T): T =
        concurrency.withPermit { withContext(Dispatchers.Default) { block() } }

    internal fun hashBlocking(password: String): String = hashWith(password, ITERATIONS, MEMORY_KB)

    internal fun hashWith(password: String, iterations: Int, memoryKb: Int): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt, iterations, memoryKb)
        return "argon2id\$$iterations\$$memoryKb\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    internal fun verifyBlocking(password: String, stored: String): Boolean {
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
