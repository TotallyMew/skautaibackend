package lt.skautai.services

import lt.skautai.database.tables.Locations
import lt.skautai.models.requests.CreateLocationRequest
import lt.skautai.models.requests.UpdateLocationRequest
import lt.skautai.models.responses.LocationListResponse
import lt.skautai.models.responses.LocationResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq


class LocationService {

    fun getLocations(tuntasId: UUID): Result<LocationListResponse> {
        return transaction {
            val locations = Locations.selectAll()
                .where { Locations.tuntasId eq tuntasId }
                .map { toLocationResponse(it) }
            Result.success(LocationListResponse(locations = locations, total = locations.size))
        }
    }

    fun getLocation(locationId: UUID, tuntasId: UUID): Result<LocationResponse> {
        return transaction {
            val location = Locations.selectAll()
                .where { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Location not found"))
            Result.success(toLocationResponse(location))
        }
    }

    fun createLocation(tuntasId: UUID, request: CreateLocationRequest): Result<LocationResponse> {
        return transaction {
            if (request.name.isBlank()) {
                return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            val locationId = Locations.insert {
                it[this.tuntasId] = tuntasId
                it[name] = request.name
                it[address] = request.address
                it[description] = request.description
            } get Locations.id

            val location = Locations.selectAll()
                .where { Locations.id eq locationId }
                .first()

            Result.success(toLocationResponse(location))
        }
    }

    fun updateLocation(
        locationId: UUID,
        tuntasId: UUID,
        request: UpdateLocationRequest
    ): Result<LocationResponse> {
        return transaction {
            Locations.selectAll()
                .where { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Location not found"))

            request.name?.let {
                if (it.isBlank()) return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            Locations.update({ (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }) {
                request.name?.let { v -> it[name] = v }
                request.address?.let { v -> it[address] = v }
                request.description?.let { v -> it[description] = v }
            }

            val updated = Locations.selectAll()
                .where { Locations.id eq locationId }
                .first()

            Result.success(toLocationResponse(updated))
        }
    }

    fun deleteLocation(locationId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            val existing = Locations.selectAll()
                .where { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Location not found"))

            // Check if any items reference this location
            val itemsUsingLocation = lt.skautai.database.tables.Items.selectAll()
                .where {
                    (lt.skautai.database.tables.Items.locationId eq locationId) and
                            (lt.skautai.database.tables.Items.status neq "INACTIVE")
                }
                .count()

            if (itemsUsingLocation > 0) {
                return@transaction Result.failure(Exception("Cannot delete location that has active items assigned to it"))
            }

            Locations.deleteWhere { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
            Result.success(Unit)
        }
    }

    private fun toLocationResponse(row: ResultRow): LocationResponse {
        return LocationResponse(
            id = row[Locations.id].toString(),
            tuntasId = row[Locations.tuntasId].toString(),
            name = row[Locations.name],
            address = row[Locations.address],
            description = row[Locations.description],
            createdAt = row[Locations.createdAt].toString()
        )
    }
}