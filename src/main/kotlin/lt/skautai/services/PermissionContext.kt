package lt.skautai.services

import lt.skautai.plugins.ResolvedPermission
import lt.skautai.plugins.resolveUserPermissions
import java.util.UUID

data class PermissionContext(
    val userId: UUID,
    val tuntasId: UUID,
    val permissions: List<ResolvedPermission>
) {
    val allUserOrgUnitIds: Set<UUID> = permissions.flatMap { it.userOrgUnitIds }.toSet()

    fun has(permissionName: String): Boolean =
        hasAll(permissionName) || scopedUnitIds(permissionName).isNotEmpty()

    fun hasAll(permissionName: String): Boolean =
        permissions.any { it.permissionName == permissionName && it.scope == "ALL" }

    fun scopedUnitIds(permissionName: String): Set<UUID> =
        permissions
            .filter { it.permissionName == permissionName && it.scope != "ALL" }
            .flatMap { it.userOrgUnitIds }
            .toSet()

    fun targetAllowed(permissionName: String, targetOrgUnitId: UUID?): Boolean {
        if (hasAll(permissionName)) return true
        val scopedUnitIds = scopedUnitIds(permissionName)
        return targetOrgUnitId != null && targetOrgUnitId in scopedUnitIds
    }
}

object PermissionContextService {
    fun resolve(userId: UUID, tuntasId: UUID): PermissionContext =
        PermissionContext(
            userId = userId,
            tuntasId = tuntasId,
            permissions = resolveUserPermissions(userId, tuntasId)
        )
}
