package com.bnuprst.expenses

import com.bnuprst.expenses.i18n.Messages

/**
 * [key] is a [Messages] key; [literal] is used instead when the text comes from elsewhere. `message` stays
 * English so logs and non-HTTP callers are unchanged; StatusPages renders the request's language.
 */
open class ApiException(
    val status: Int,
    val key: String,
    val args: List<Any?> = emptyList(),
    val literal: String? = null,
) : RuntimeException(literal ?: Messages.format(key, args))

class BadRequest(key: String, vararg args: Any?) : ApiException(400, key, args.toList())
class Unauthorized(key: String = "error.unauthorized", vararg args: Any?) : ApiException(401, key, args.toList())
class Forbidden(key: String = "error.forbidden", vararg args: Any?) : ApiException(403, key, args.toList())
class NotFound(key: String = "error.not_found", vararg args: Any?) : ApiException(404, key, args.toList())
class Conflict(key: String, vararg args: Any?) : ApiException(409, key, args.toList())
