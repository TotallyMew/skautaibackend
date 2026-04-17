package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.responses.ErrorResponse
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.userRoutes() {
    authenticate("auth-jwt") {
        route("/api/users/me") {
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