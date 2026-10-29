package com.bnuprst.expenses

import com.bnuprst.expenses.shared.AddMemberRequest
import com.bnuprst.expenses.shared.CreateBankConnectionRequest
import com.bnuprst.expenses.shared.CreateRuleRequest
import com.bnuprst.expenses.shared.CreateTransactionRequest
import com.bnuprst.expenses.shared.CreateWalletRequest
import com.bnuprst.expenses.shared.ImportRequest
import com.bnuprst.expenses.shared.LoginRequest
import com.bnuprst.expenses.shared.RegisterRequest
import com.bnuprst.expenses.shared.TxnSort
import com.bnuprst.expenses.shared.TxnStatus
import com.bnuprst.expenses.shared.UpdateTransactionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.YearMonth

private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val log = LoggerFactory.getLogger("Routes")

fun Application.routes(s: Services) = routing {
    get("/health") { call.respondText("ok") }

    route("/api/v1") {
        post("/auth/register") { call.respond(HttpStatusCode.Created, s.auth.register(call.receive<RegisterRequest>())) }
        post("/auth/login") { call.respond(s.auth.login(call.receive<LoginRequest>())) }

        // Bank redirect target: unauthenticated, identified by the one-time `state`.
        get("/banking/callback") {
            val banking = s.banking ?: throw NotFound("Bank sync is not configured")
            val p = call.request.queryParameters
            val (redirect, connectionId) = banking.handleCallback(p["state"], p["code"], p["error"], p["error_description"])
            connectionId?.let { id ->
                backgroundScope.launch { runCatching { banking.sync(id) }.onFailure { log.error("Initial sync failed", it) } }
            }
            call.respondRedirect(redirect)
        }

        authenticate("user") {
            walletRoutes(s)
            bankingRoutes(s)
            patch("/transactions/{id}") {
                val req = call.receive<UpdateTransactionRequest>()
                call.respond(s.transactions.setCategory(call.userId(), call.longParam("id"), req.categoryId))
            }
        }
    }
}

private fun Route.walletRoutes(s: Services) = route("/wallets") {
    get { call.respond(s.wallets.list(call.userId())) }
    post { call.respond(HttpStatusCode.Created, s.wallets.create(call.userId(), call.receive<CreateWalletRequest>())) }

    route("/{walletId}") {
        get("/members") { call.respond(s.wallets.members(call.userId(), call.longParam("walletId"))) }
        post("/members") {
            val req = call.receive<AddMemberRequest>()
            call.respond(HttpStatusCode.Created, s.wallets.addMember(call.userId(), call.longParam("walletId"), req.email))
        }
        get("/categories") { call.respond(s.wallets.categories(call.userId(), call.longParam("walletId"))) }
        get("/rules") { call.respond(s.transactions.rules(call.userId(), call.longParam("walletId"))) }
        post("/rules") {
            val req = call.receive<CreateRuleRequest>()
            call.respond(HttpStatusCode.Created, s.transactions.createRule(call.userId(), call.longParam("walletId"), req))
        }
        get("/transactions") {
            val q = call.request.queryParameters
            call.respond(
                s.transactions.list(
                    call.userId(), call.longParam("walletId"),
                    from = q["from"]?.let(::parseDate),
                    to = q["to"]?.let(::parseDate),
                    status = q["status"]?.let { runCatching { TxnStatus.valueOf(it.uppercase()) }.getOrNull() ?: throw BadRequest("Invalid status") },
                    categoryId = q["category"]?.takeIf { it != "none" }?.let { it.toLongOrNull() ?: throw BadRequest("Invalid category") },
                    query = q["q"],
                    uncategorized = q["category"] == "none",
                    sort = q["sort"]?.let { runCatching { TxnSort.valueOf(it.uppercase()) }.getOrNull() ?: throw BadRequest("Invalid sort") }
                        ?: TxnSort.DATE_DESC,
                    limit = q["limit"]?.toIntOrNull() ?: 200,
                    offset = q["offset"]?.toLongOrNull() ?: 0,
                )
            )
        }
        get("/stats") {
            val q = call.request.queryParameters
            val month = YearMonth.now()
            call.respond(
                s.transactions.stats(
                    call.userId(), call.longParam("walletId"),
                    from = q["from"]?.let(::parseDate) ?: month.atDay(1),
                    to = q["to"]?.let(::parseDate) ?: month.atEndOfMonth(),
                )
            )
        }
        post("/transactions") {
            val req = call.receive<CreateTransactionRequest>()
            call.respond(HttpStatusCode.Created, s.transactions.create(call.userId(), call.longParam("walletId"), req))
        }
        post("/import") {
            val req = call.receive<ImportRequest>()
            call.respond(s.imports.import(call.userId(), call.longParam("walletId"), req))
        }
        post("/import/finalize") {
            val source = call.request.queryParameters["source"] ?: throw BadRequest("source is required")
            call.respond(s.imports.finalize(call.userId(), call.longParam("walletId"), source))
        }
        // Everything that still needs a human: low-confidence suggestions and rows nothing could categorize.
        get("/review") { call.respond(s.transactions.reviewQueue(call.userId(), call.longParam("walletId"))) }
        post("/bank-connections") {
            val banking = s.banking ?: throw NotFound("Bank sync is not configured")
            val req = call.receive<CreateBankConnectionRequest>()
            call.respond(HttpStatusCode.Created, banking.connect(call.userId(), call.longParam("walletId"), req.aspspName, req.country))
        }
    }
}

private fun Route.bankingRoutes(s: Services) {
    get("/banking/aspsps") {
        val banking = s.banking ?: throw NotFound("Bank sync is not configured")
        call.respond(banking.aspsps(call.request.queryParameters["country"]))
    }
    route("/bank-connections") {
        get {
            val banking = s.banking ?: throw NotFound("Bank sync is not configured")
            call.respond(banking.list(call.userId(), call.request.queryParameters["wallet"]?.toLongOrNull()))
        }
        post("/{id}/sync") {
            val banking = s.banking ?: throw NotFound("Bank sync is not configured")
            call.respond(banking.syncForUser(call.userId(), call.longParam("id")))
        }
        post("/{id}/reauthorize") {
            val banking = s.banking ?: throw NotFound("Bank sync is not configured")
            call.respond(banking.reauthorize(call.userId(), call.longParam("id")))
        }
        delete("/{id}") {
            val banking = s.banking ?: throw NotFound("Bank sync is not configured")
            banking.delete(call.userId(), call.longParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.userId(): Long =
    principal<JWTPrincipal>()?.payload?.subject?.toLongOrNull() ?: throw Unauthorized()

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw BadRequest("Invalid $name")

private fun parseDate(s: String): LocalDate = runCatching { LocalDate.parse(s) }.getOrElse { throw BadRequest("Invalid date: $s") }
