CREATE TABLE IF NOT EXISTS event_packing_containers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'BOX',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    sort_order INTEGER NOT NULL DEFAULT 0,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_packing_containers_event
ON event_packing_containers(event_id, sort_order, name);

CREATE TABLE IF NOT EXISTS event_packing_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
    allocation_id UUID NULL REFERENCES event_inventory_allocations(id) ON DELETE SET NULL,
    container_id UUID NULL REFERENCES event_packing_containers(id) ON DELETE SET NULL,
    bucket_id UUID NULL REFERENCES event_inventory_buckets(id) ON DELETE SET NULL,
    item_id UUID NULL REFERENCES items(id) ON DELETE SET NULL,
    item_name VARCHAR(200) NOT NULL,
    required_quantity INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'TODO',
    source_summary VARCHAR(700) NULL,
    notes TEXT NULL,
    checked_by_user_id UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    checked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_packing_lines_event
ON event_packing_lines(event_id, status);

CREATE INDEX IF NOT EXISTS idx_event_packing_lines_container
ON event_packing_lines(container_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_packing_line_allocation
ON event_packing_lines(event_id, allocation_id)
WHERE allocation_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_packing_line_unallocated_item
ON event_packing_lines(event_id, event_inventory_item_id)
WHERE allocation_id IS NULL;
