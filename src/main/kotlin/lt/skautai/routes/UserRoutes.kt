package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.resolveUserPermissions
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.userRoutes() {
    authenticate("auth-jwt") {
        route("/api/users/me") {
            get("/permissions") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasIdHeader = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing X-Tuntas-Id header"))
                val tuntasId = try { UUID.fromString(tuntasIdHeader) }
                    catch (e: Exception) { return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID")) }

                val isMember = transaction {
                    UserTuntasMemberships.selectAll().where {
                        (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
                    }.firstOrNull() != null
                }
                if (!isMember) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))

                val resolvedPermissions = resolveUserPermissions(userId, tuntasId)
                val perms = (
                    resolvedPermissions.map { it.permissionName } +
                        resolvedPermissions.map { "${it.permissionName}:${it.scope}" }
                    ).distinct()

                call.respond(HttpStatusCode.OK, mapOf("permissions" to perms))
            }

            get("/tuntai") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntai = transaction {
                    UserTuntasMemberships
                        .innerJoin(Tuntai, { UserTuntasMemberships.tuntasId }, { Tuntai.id })
                        .selectAll()
                        .where {
                            (UserTuntasMemberships.userId eq userId) and
                                    (UserTuntasMemberships.leftAt.isNull()) and
                                    (Tuntai.status eq "ACTIVE")
                        }
                        .map {
                            mapOf(
                                "id" to it[Tuntai.id].toString(),
                                "name" to it[Tuntai.name],
                                "krastas" to (it[Tuntai.krastas] ?: ""),
                                "contactEmail" to (it[Tuntai.contactEmail] ?: "")
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, tuntai)
            }
        }
    }
}
