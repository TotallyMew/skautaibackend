package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Roles
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.RoleListResponse
import lt.skautai.models.responses.RoleResponse
import lt.skautai.plugins.checkPermission
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.rolesRoutes() {
    authenticate("auth-jwt") {
        route("/api/roles") {
            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))

                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("members.view", tuntasUUID)) return@get

                val roles = transaction {
                    Roles.selectAll()
                        .where { Roles.tuntasId eq tuntasUUID }
                        .map {
                            RoleResponse(
                                id = it[Roles.id].toString(),
                                name = it[Roles.name],
                                roleType = it[Roles.roleType],
                                isSystemRole = it[Roles.isSystemRole]
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, RoleListResponse(roles = roles, total = roles.size))
            }
        }
    }
}