package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.Devices
import lt.skautai.models.requests.RegisterDeviceRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class DeviceService {
    fun registerDevice(userId: UUID, request: RegisterDeviceRequest): Result<Unit> = transaction {
        val token = request.deviceToken.trim()
        if (token.isBlank()) {
            return@transaction Result.failure(IllegalArgumentException("Device token is required"))
        }

        val now = Clock.System.now()
        val existing = Devices.selectAll()
            .where { Devices.deviceToken eq token }
            .firstOrNull()

        if (existing == null) {
            Devices.insert {
                it[Devices.userId] = userId
                it[deviceName] = request.deviceName?.trim()?.takeIf(String::isNotBlank)
                it[deviceToken] = token
                it[lastSyncAt] = now
                it[createdAt] = now
            }
        } else {
            Devices.update({ Devices.deviceToken eq token }) {
                it[Devices.userId] = userId
                it[deviceName] = request.deviceName?.trim()?.takeIf(String::isNotBlank)
                it[lastSyncAt] = now
            }
        }

        Result.success(Unit)
    }

    fun deviceTokensForUser(userId: UUID): List<String> = transaction {
        Devices.select(Devices.deviceToken)
            .where { Devices.userId eq userId }
            .map { it[Devices.deviceToken] }
    }

    fun unregisterDevice(userId: UUID, deviceToken: String): Result<Unit> = transaction {
        val token = deviceToken.trim()
        if (token.isBlank()) {
            return@transaction Result.failure(IllegalArgumentException("Device token is required"))
        }
        Devices.deleteWhere {
            (Devices.userId eq userId) and (Devices.deviceToken eq token)
        }
        Result.success(Unit)
    }

    fun deleteDeviceToken(deviceToken: String) = transaction {
        Devices.deleteWhere { Devices.deviceToken eq deviceToken }
    }
}
