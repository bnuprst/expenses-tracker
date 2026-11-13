package com.bnuprst.expenses.fx

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A provider that never touches the network and counts fetches, for tests. */
class FakeFxProvider(var days: List<FxDay> = emptyList()) : FxProvider {
    override val source = "fake"
    private val lock = Any()
    private val callList = mutableListOf<Pair<LocalDate, LocalDate>>()

    var fail = false

    val calls: List<Pair<LocalDate, LocalDate>>
        get() = synchronized(lock) { callList.toList() }

    override suspend fun rates(from: LocalDate, to: LocalDate, base: String): List<FxDay> = synchronized(lock) {
        callList += from to to
        if (fail) throw FxUnavailable("fake provider failure")
        // Only the requested sub-range, so an "empty response" is expressible.
        days.filter { it.asOf in from..to && it.base.equals(base, ignoreCase = true) }
    }
}

/** A fixed clock whose instant tests can move forward. */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}
