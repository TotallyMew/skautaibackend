CREATE TABLE IF NOT EXISTS event_inventory_transfer_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    source_custody_id UUID NOT NULL REFERENCES event_inventory_custody(id),
    event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id),
    requested_by_user_id UUID NOT NULL REFERENCES users(id),
    requested_from_user_id UUID NOT NULL REFERENCES users(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP,
    responded_by_user_id UUID REFERENCES users(id),
    movement_id UUID REFERENCES event_inventory_movements(id)
);

CREATE INDEX IF NOT EXISTS idx_event_transfer_requests_event_status
ON event_inventory_transfer_requests(event_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_event_transfer_requests_from_user
ON event_inventory_transfer_requests(requested_from_user_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_event_transfer_requests_requester
ON event_inventory_transfer_requests(requested_by_user_id, status, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_event_transfer_requests_pending_unique
ON event_inventory_transfer_requests(source_custody_id, requested_by_user_id)
WHERE status = 'PENDING';
