package com.bnuprst.expenses.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {
    @Test
    fun `round trip and wrong password`() = runBlocking {
        val h = PasswordHasher.hash("correct horse")
        assertTrue(h.startsWith("argon2id\$2\$19456\$"))
        assertTrue(PasswordHasher.verify("correct horse", h))
        assertFalse(PasswordHasher.verify("wrong", h))
    }

    @Test
    fun `hashes made with the earlier 64 MiB parameters still verify`() = runBlocking {
        val old = PasswordHasher.hashWith("legacy", 3, 64 * 1024)
        assertTrue(PasswordHasher.verify("legacy", old))
    }

    @Test
    fun `concurrent logins are throttled rather than failing`() = runBlocking {
        val h = PasswordHasher.hash("pw")
        val results = (1..8).map { async { PasswordHasher.verify("pw", h) } }.awaitAll()
        assertTrue(results.all { it })
    }
}
