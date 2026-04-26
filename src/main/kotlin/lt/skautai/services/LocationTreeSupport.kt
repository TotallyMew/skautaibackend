package lt.skautai.services

import lt.skautai.database.tables.Locations
import org.jetbrains.exposed.sql.ResultRow
import java.util.UUID

internal data class LocationNodeData(
    val id: UUID,
    val name: String,
    val visibility: String,
    val parentLocationId: UUID?,
    val ownerUserId: UUID?,
    val ownerUnitId: UUID?
)

internal fun ResultRow.toLocationNodeData(): LocationNodeData = LocationNodeData(
    id = this[Locations.id],
    name = this[Locations.name],
    visibility = this[Locations.visibility],
    parentLocationId = this[Locations.parentLocationId],
    ownerUserId = this[Locations.ownerUserId],
    ownerUnitId = this[Locations.ownerUnitId]
)

internal fun buildLocationPath(
    locationId: UUID,
    nodesById: Map<UUID, LocationNodeData>
): String {
    val parts = mutableListOf<String>()
    val visited = mutableSetOf<UUID>()
    var currentId: UUID? = locationId
    while (currentId != null && visited.add(currentId)) {
        val node = nodesById[currentId] ?: break
        parts += node.name
        currentId = node.parentLocationId
    }
    return parts.asReversed().joinToString(" / ")
}
