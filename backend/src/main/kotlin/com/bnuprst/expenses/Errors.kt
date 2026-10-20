package com.bnuprst.expenses

open class ApiException(val status: Int, message: String) : RuntimeException(message)
class BadRequest(message: String) : ApiException(400, message)
class Unauthorized(message: String = "Unauthorized") : ApiException(401, message)
class Forbidden(message: String = "Forbidden") : ApiException(403, message)
class NotFound(message: String = "Not found") : ApiException(404, message)
class Conflict(message: String) : ApiException(409, message)
