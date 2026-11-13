package com.bnuprst.expenses.android

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.ConnectionStatus
import com.bnuprst.expenses.shared.WalletDto
import com.bnuprst.expenses.shared.WalletRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for a slow response that started before a language switch finishing afterwards.
 * The old-language result must never overwrite the refetch started by [AppViewModel.onUiLanguage].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppViewModelLanguageTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; run it on the test thread instead.
        Dispatchers.setMain(Dispatchers.Unconfined)
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun connectionJson(lastSyncError: String) =
        """[{"id":1,"walletId":1,"aspspName":"Nordea","country":"FI","status":"ACTIVE","renewSoon":false,"lastSyncError":"$lastSyncError"}]"""

    private suspend fun await(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    @Test
    fun `a delayed response in the old language cannot overwrite the refetch`() = runBlocking {
        val releaseOld = CompletableDeferred<Unit>()
        val connectionCalls = AtomicInteger(0)
        val categoriesCalls = AtomicInteger(0)
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/bank-connections") -> {
                    if (connectionCalls.incrementAndGet() == 1) {
                        releaseOld.await()
                        respond(connectionJson("Old English text"), HttpStatusCode.OK, jsonHeaders())
                    } else {
                        respond(connectionJson("Новий український текст"), HttpStatusCode.OK, jsonHeaders())
                    }
                }
                request.url.encodedPath.endsWith("/categories") -> {
                    categoriesCalls.incrementAndGet()
                    respond("[]", HttpStatusCode.OK, jsonHeaders())
                }
                request.url.encodedPath.endsWith("/review") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath.endsWith("/transactions") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }
        val vm = AppViewModel(application, Api({ "https://example.test" }, { "t" }, { "en" }, engine))
        vm.onUiLanguage("en")   // a cold start registers the language the data was loaded with
        vm.openWallet(WalletDto(1, "Family", "EUR", WalletRole.OWNER))
        await("initial refresh") { categoriesCalls.get() >= 1 && vm.state.value.busy == 0 }

        vm.selectTab(WalletTab.BANKS)
        await("old language bank-connections request") { connectionCalls.get() == 1 }

        // The language change starts a second, Ukrainian fetch while the English one is still in flight.
        vm.onUiLanguage("uk")
        await("ukrainian refetch") { vm.state.value.connections.singleOrNull()?.lastSyncError == "Новий український текст" }

        // The English response now lands late; it must be rejected.
        releaseOld.complete(Unit)
        await("old response processed") { vm.state.value.busy == 0 }

        assertEquals("Новий український текст", vm.state.value.connections.single().lastSyncError)
    }

    @Test
    fun `a delayed sync cannot publish a snackbar in the old language`() = runBlocking {
        val releaseSync = CompletableDeferred<Unit>()
        val syncCalls = AtomicInteger(0)
        val categoriesCalls = AtomicInteger(0)
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/sync") -> {
                    syncCalls.incrementAndGet()
                    releaseSync.await()
                    respond(
                        """{"fetched":0,"inserted":0,"error":"A sync is already running for this connection"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }
                request.url.encodedPath.endsWith("/categories") -> {
                    categoriesCalls.incrementAndGet()
                    respond("[]", HttpStatusCode.OK, jsonHeaders())
                }
                request.url.encodedPath.endsWith("/review") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath.endsWith("/transactions") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath.endsWith("/bank-connections") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }
        val vm = AppViewModel(application, Api({ "https://example.test" }, { "t" }, { "en" }, engine))
        vm.onUiLanguage("en")
        vm.openWallet(WalletDto(1, "Family", "EUR", WalletRole.OWNER))
        await("initial refresh") { categoriesCalls.get() >= 1 && vm.state.value.busy == 0 }

        val connection = BankConnectionDto(
            id = 7, walletId = 1, aspspName = "Nordea", country = "FI",
            status = ConnectionStatus.ACTIVE, renewSoon = false,
        )
        vm.sync(connection)
        await("sync in flight") { syncCalls.get() == 1 }

        // The switch clears messages; the English sync may not put its message back afterwards.
        vm.onUiLanguage("uk")
        await("refetch after switch") { categoriesCalls.get() >= 2 }

        releaseSync.complete(Unit)
        await("all work done") { vm.state.value.busy == 0 }

        assertNull(vm.state.value.message)
    }

    @Test
    fun `a delayed sync applies its final server state after a language switch`() = runBlocking {
        val releaseSync = CompletableDeferred<Unit>()
        val syncCalls = AtomicInteger(0)
        val categoriesCalls = AtomicInteger(0)
        val connectionCalls = AtomicInteger(0)
        val syncDone = AtomicBoolean(false)
        val languageTag = AtomicReference("en")
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/sync") -> {
                    syncCalls.incrementAndGet()
                    releaseSync.await()
                    respond("""{"fetched":3,"inserted":2}""", HttpStatusCode.OK, jsonHeaders())
                }
                request.url.encodedPath.endsWith("/bank-connections") -> {
                    connectionCalls.incrementAndGet()
                    val lang = request.headers[HttpHeaders.AcceptLanguage]
                    val body = when {
                        // The sync has finished: this is the final server state, with no error left.
                        syncDone.get() -> """[{"id":1,"walletId":1,"aspspName":"Nordea","country":"FI","status":"ACTIVE","renewSoon":false}]"""
                        lang == "uk" -> connectionJson("Стара помилка синхронізації")
                        else -> connectionJson("Old English sync error")
                    }
                    respond(body, HttpStatusCode.OK, jsonHeaders())
                }
                request.url.encodedPath.endsWith("/categories") -> {
                    categoriesCalls.incrementAndGet()
                    respond("[]", HttpStatusCode.OK, jsonHeaders())
                }
                request.url.encodedPath.endsWith("/review") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath.endsWith("/transactions") -> respond("[]", HttpStatusCode.OK, jsonHeaders())
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }
        val vm = AppViewModel(application, Api({ "https://example.test" }, { "t" }, { languageTag.get() }, engine))
        vm.onUiLanguage("en")
        vm.openWallet(WalletDto(1, "Family", "EUR", WalletRole.OWNER))
        await("initial refresh") { categoriesCalls.get() >= 1 && vm.state.value.busy == 0 }

        vm.selectTab(WalletTab.BANKS)
        await("banks loaded before the sync") {
            vm.state.value.connections.singleOrNull()?.lastSyncError == "Old English sync error"
        }

        val connection = BankConnectionDto(
            id = 7, walletId = 1, aspspName = "Nordea", country = "FI",
            status = ConnectionStatus.ACTIVE, renewSoon = false,
        )
        vm.sync(connection)
        await("sync in flight") { syncCalls.get() == 1 }

        // The switch refetches the open Banks tab while the sync is still running.
        languageTag.set("uk")
        vm.onUiLanguage("uk")
        await("refetch after switch") {
            vm.state.value.connections.singleOrNull()?.lastSyncError == "Стара помилка синхронізації"
        }

        // Let the sync finish. Its follow-up read starts now, in the current language, and is newer than
        // the switch's refresh, so it must be applied rather than discarded with the mutation's generation.
        syncDone.set(true)
        releaseSync.complete(Unit)
        await("follow-up read applied") { vm.state.value.connections.singleOrNull()?.lastSyncError == null }
        await("all work done") { vm.state.value.busy == 0 }

        assertEquals(3, connectionCalls.get())
        // The sync's own snackbar was produced in the old language and stays dropped.
        assertNull(vm.state.value.message)
    }
}
