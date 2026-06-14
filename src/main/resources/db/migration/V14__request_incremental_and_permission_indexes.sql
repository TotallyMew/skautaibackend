CREATE INDEX IF NOT EXISTS idx_draugove_requisition_items_requisition
ON draugove_requisition_items(requisition_id);

CREATE INDEX IF NOT EXISTS idx_draugove_requisitions_tuntas_updated_at
ON draugove_requisitions(tuntas_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_bendras_inventory_requests_tuntas_updated_at
ON bendras_inventory_requests(tuntas_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_reservations_tuntas_updated_at
ON reservations(tuntas_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_events_tuntas_updated_at
ON events(tuntas_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_user_tuntas_memberships_active_user_tuntas
ON user_tuntas_memberships(user_id, tuntas_id)
WHERE left_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_leadership_roles_active_user_tuntas
ON user_leadership_roles(user_id, tuntas_id, role_id, organizational_unit_id)
WHERE term_status = 'ACTIVE' AND left_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_unit_assignments_active_user_tuntas
ON unit_assignments(user_id, tuntas_id, organizational_unit_id)
WHERE left_at IS NULL;
