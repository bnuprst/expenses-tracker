package com.bnuprst.expenses.auth

import com.bnuprst.expenses.BadRequest
import com.bnuprst.expenses.Conflict
import com.bnuprst.expenses.Unauthorized
import com.bnuprst.expenses.db.Users
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.shared.AuthResponse
import com.bnuprst.expenses.shared.LoginRequest
import com.bnuprst.expenses.shared.RegisterRequest
import com.bnuprst.expenses.shared.UserDto
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class AuthService(private val jwt: JwtService) {

    suspend fun register(req: RegisterRequest): AuthResponse {
        val email = req.email.trim().lowercase()
        if (!email.contains('@')) throw BadRequest("Invalid email")
        if (req.password.length < 8) throw BadRequest("Password must be at least 8 characters")
        if (req.name.isBlank()) throw BadRequest("Name is required")
        val hash = PasswordHasher.hash(req.password)
        val user = dbQuery {
            if (Users.selectAll().where { Users.email eq email }.any()) throw Conflict("Email already registered")
            val id = Users.insert {
                it[Users.email] = email
                it[name] = req.name.trim()
                it[passwordHash] = hash
            }[Users.id]
            UserDto(id, email, req.name.trim())
        }
        return AuthResponse(jwt.issue(user.id), user)
    }

    suspend fun login(req: LoginRequest): AuthResponse {
        val email = req.email.trim().lowercase()
        val row = dbQuery { Users.selectAll().where { Users.email eq email }.singleOrNull() }
            ?: throw Unauthorized("Invalid email or password")
        if (!PasswordHasher.verify(req.password, row[Users.passwordHash])) throw Unauthorized("Invalid email or password")
        val user = row.toUserDto()
        return AuthResponse(jwt.issue(user.id), user)
    }
}

fun ResultRow.toUserDto() = UserDto(this[Users.id], this[Users.email], this[Users.name])
