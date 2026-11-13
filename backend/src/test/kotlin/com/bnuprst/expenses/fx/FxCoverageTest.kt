package com.bnuprst.expenses.fx

import com.bnuprst.expenses.TestDb
import com.bnuprst.expenses.config.FxConfig
import com.bnuprst.expenses.db.FxCoverage
import com.bnuprst.expenses.db.FxRates
import com.bnuprst.expenses.db.dbQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FxCoverageTest {
    private val config = FxConfig(
        enabled = true,
        apiBase = "fake",
        base = "EUR",
        refreshInterval = Duration.ofHours(12),
        maxStaleDays = 7,
        backfillDays = 400,
        provisionalDays = 5,
    )

    private fun service(provider: FakeFxProvider, clock: MutableClock) = FxService(provider, config, clock)

    private fun date(text: String) = LocalDate.parse(text)

    @BeforeTest
    fun reset() {
        TestDb.reset()
    }

    private suspend fun cover(base: String, from: LocalDate, to: LocalDate, published: Boolean, at: OffsetDateTime) {
        dbQuery {
            generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.forEach { day ->
                FxCoverage.insertIgnore {
                    it[FxCoverage.base] = base
                    it[FxCoverage.asOf] = day
                    it[FxCoverage.published] = published
                    it[FxCoverage.fetchedAt] = at
                }
            }
        }
    }

    private suspend fun seedRate(asOf: LocalDate, quote: String, value: String) {
        dbQuery {
            FxRates.insertIgnore {
                it[FxRates.asOf] = asOf
                it[FxRates.base] = "EUR"
                it[FxRates.quote] = quote
                it[FxRates.rate] = BigDecimal(value)
                it[FxRates.rateSource] = "seed"
            }
        }
    }

    private suspend fun coverageCount(from: LocalDate, to: LocalDate): Long = dbQuery {
        val count = FxCoverage.asOf.count()
        FxCoverage.select(count).where {
            (FxCoverage.base eq "EUR") and
                (FxCoverage.asOf greaterEq from) and (FxCoverage.asOf lessEq to)
        }.single()[count]
    }

    @Test
    fun `an older range is fetched even when recent days are covered`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider = FakeFxProvider(listOf(FxDay(date("2024-05-02"), "EUR", mapOf("PLN" to BigDecimal("4.30")))))
        val fx = service(provider, clock)
        cover("EUR", date("2026-11-01"), date("2026-11-13"), true, OffsetDateTime.now(clock))

        val stored = fx.ensureCovered(date("2024-05-01"), date("2024-05-02"))
        assertEquals(listOf(date("2024-05-02")), stored)
        assertEquals(listOf(date("2024-04-24") to date("2024-05-02")), provider.calls)

        val table = fx.table(setOf("PLN"), date("2024-05-02"), date("2024-05-02"))
        val converted = table.convert(BigDecimal("43"), "PLN", "EUR", date("2024-05-02"))
        assertNotNull(converted)
        assertEquals(0, BigDecimal("10").compareTo(converted.amount))
    }

    @Test
    fun `an interior gap is refetched`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider = FakeFxProvider(emptyList())
        val fx = service(provider, clock)
        cover("EUR", date("2024-04-24"), date("2024-05-03"), true, OffsetDateTime.now(clock))
        cover("EUR", date("2024-05-05"), date("2024-05-06"), true, OffsetDateTime.now(clock))

        fx.ensureCovered(date("2024-05-01"), date("2024-05-06"))
        assertEquals(listOf(date("2024-05-04") to date("2024-05-04")), provider.calls)
    }

    @Test
    fun `a second call over the same range makes no provider call`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider = FakeFxProvider(listOf(FxDay(date("2024-05-02"), "EUR", mapOf("PLN" to BigDecimal("4.30")))))
        val fx = service(provider, clock)

        fx.ensureCovered(date("2024-05-01"), date("2024-05-02"))
        assertEquals(1, provider.calls.size)
        fx.ensureCovered(date("2024-05-01"), date("2024-05-02"))
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `a provider failure writes no coverage rows`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider = FakeFxProvider(emptyList()).apply { fail = true }
        val fx = service(provider, clock)

        assertEquals(emptyList(), fx.ensureCovered(date("2024-05-01"), date("2024-05-02")))
        assertEquals(0, coverageCount(date("2024-04-24"), date("2024-05-02")))
    }

    @Test
    fun `a malformed rate response leaves coverage absent so the day is retried`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val engine = MockEngine {
            respond(
                """{"base":"EUR","rates":{"2024-05-02":{"USD":"invalid","PLN":"4.30"}}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val fx = FxService(FrankfurterClient(HttpClient(engine), config), config, clock)

        assertEquals(emptyList(), fx.ensureCovered(date("2024-05-01"), date("2024-05-02")))
        assertEquals(0, coverageCount(date("2024-04-24"), date("2024-05-02")))
    }

    @Test
    fun `provisional coverage is retried until the rate is published`() = runBlocking {
        val start = Instant.parse("2026-11-13T00:00:00Z")
        val clock = MutableClock(start)
        val provider = FakeFxProvider(emptyList())
        val fx = service(provider, clock)
        val today = date("2026-11-13")

        fx.ensureCovered(today, today)
        assertEquals(1, provider.calls.size)
        // Throttled: the just-written fetched_at is newer than refreshInterval.
        fx.ensureCovered(today, today)
        assertEquals(1, provider.calls.size)

        clock.now = start.plus(config.refreshInterval).plusSeconds(1)
        provider.days = listOf(FxDay(today, "EUR", mapOf("PLN" to BigDecimal("4.30"))))
        val stored = fx.ensureCovered(today, today)
        assertEquals(listOf(today), stored)
        assertEquals(2, provider.calls.size)

        val converted = fx.table(setOf("PLN"), today, today).convert(BigDecimal("43"), "PLN", "EUR", today)
        assertNotNull(converted)
        assertEquals(0, BigDecimal("10").compareTo(converted.amount))
    }

    @Test
    fun `an unpublished day older than provisionalDays is not retried`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider = FakeFxProvider(emptyList())
        val fx = service(provider, clock)
        val old = date("2026-10-01")
        cover("EUR", old.minusDays(7), old.minusDays(1), true, OffsetDateTime.now(clock).minusDays(30))
        cover("EUR", old, old, false, OffsetDateTime.now(clock).minusDays(30))

        fx.ensureCovered(old, old)
        assertTrue(provider.calls.isEmpty(), "an old unpublished day must not be retried: ${provider.calls}")
    }

    @Test
    fun `notify=false returns the stored dates and notifyRatesStored dispatches once off the call stack`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider = FakeFxProvider(listOf(FxDay(date("2024-05-02"), "EUR", mapOf("PLN" to BigDecimal("4.30")))))
        val fx = service(provider, clock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fx.startScheduler(scope)

        val latch = CountDownLatch(1)
        var delivered: List<LocalDate>? = null
        fx.onRatesStored = { dates ->
            delivered = dates
            latch.countDown()
        }
        try {
            val stored = fx.ensureCovered(date("2024-05-01"), date("2024-05-02"), notify = false)
            assertEquals(listOf(date("2024-05-02")), stored)
            assertEquals(1, latch.count, "notify=false must not invoke the callback inline")

            fx.notifyRatesStored(stored)
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the callback was never dispatched")
            assertEquals(stored, delivered)
        } finally {
            scope.cancel()
        }
    }
}
