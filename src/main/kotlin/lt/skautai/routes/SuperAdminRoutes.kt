package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.datetime.Clock
import java.util.*

fun Route.superAdminRoutes() {
    authenticate("auth-super-admin") {
        route("/api/super-admin/tuntai") {
            get {
                val tuntai = transaction {
                    Tuntai.selectAll().map {
                        mapOf(
                            "id" to it[Tuntai.id].toString(),
                            "name" to it[Tuntai.name],
                            "krastas" to (it[Tuntai.krastas] ?: ""),
                            "status" to it[Tuntai.status],
                            "contactEmail" to (it[Tuntai.contactEmail] ?: "")
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, tuntai)
            }

            post("/{id}/approve") {
                val tuntasId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val principal = call.principal<JWTPrincipal>()!!
                val adminId = UUID.fromString(principal.getClaim("userId", String::class))

                val result = transaction {
                    val tuntas = Tuntai.selectAll()
                        .where { Tuntai.id eq tuntasId }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Tuntas not found"))

                    if (tuntas[Tuntai.status] != "PENDING") {
                        return@transaction Result.failure(Exception("Tuntas is not pending approval"))
                    }

                    Tuntai.update({ Tuntai.id eq tuntasId }) {
                        it[status] = "ACTIVE"
                        it[approvedBySuperAdminId] = adminId
                        it[approvedAt] = Clock.System.now()
                    }

                    Result.success(MessageResponse("Tuntas approved successfully"))
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Approval failed")) }
            }

            post("/{id}/reject") {
                val tuntasId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val result = transaction {
                    val tuntas = Tuntai.selectAll()
                        .where { Tuntai.id eq tuntasId }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Tuntas not found"))

                    if (tuntas[Tuntai.status] != "PENDING") {
                        return@transaction Result.failure(Exception("Tuntas is not pending approval"))
                    }

                    Tuntai.update({ Tuntai.id eq tuntasId }) {
                        it[status] = "REJECTED"
                    }

                    Result.success(MessageResponse("Tuntas rejected"))
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Rejection failed")) }
            }

        }
    }
}