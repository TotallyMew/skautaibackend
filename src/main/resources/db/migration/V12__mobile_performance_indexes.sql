CREATE INDEX IF NOT EXISTS idx_items_tuntas_status_custodian_type
ON items(tuntas_id, status, custodian_id, type);

CREATE INDEX IF NOT EXISTS idx_items_tuntas_updated_at
ON items(tuntas_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_reservations_tuntas_status_group
ON reservations(tuntas_id, status, group_id);

CREATE INDEX IF NOT EXISTS idx_reservations_tuntas_user_status
ON reservations(tuntas_id, reserved_by_user_id, status);

CREATE INDEX IF NOT EXISTS idx_reservations_tuntas_unit_status
ON reservations(tuntas_id, requesting_unit_id, status);

CREATE INDEX IF NOT EXISTS idx_reservation_movements_group_item_type
ON reservation_movements(reservation_group_id, item_id, type);

CREATE INDEX IF NOT EXISTS idx_draugove_requisitions_tuntas_review
ON draugove_requisitions(tuntas_id, top_level_review_status, unit_review_status);

CREATE INDEX IF NOT EXISTS idx_draugove_requisitions_tuntas_unit_user
ON draugove_requisitions(tuntas_id, organizational_unit_id, created_by_user_id);

CREATE INDEX IF NOT EXISTS idx_bendras_requests_tuntas_status_unit
ON bendras_inventory_requests(tuntas_id, top_level_status, requesting_unit_id);

CREATE INDEX IF NOT EXISTS idx_events_tuntas_status_start
ON events(tuntas_id, status, start_date);

CREATE INDEX IF NOT EXISTS idx_event_roles_user_role_event
ON event_roles(user_id, role, event_id);

CREATE INDEX IF NOT EXISTS idx_event_inventory_items_event_purchase
ON event_inventory_items(event_id, needs_purchase);

CREATE INDEX IF NOT EXISTS idx_event_packing_lines_event_status
ON event_packing_lines(event_id, status);

CREATE INDEX IF NOT EXISTS idx_item_check_sessions_tuntas_status_scope
ON item_check_sessions(tuntas_id, status, scope_custodian_id);
