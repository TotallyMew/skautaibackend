CREATE TABLE IF NOT EXISTS inventory_kits (
    id UUID PRIMARY KEY,
    tuntas_id UUID NOT NULL REFERENCES tuntai(id),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_by_user_id UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS inventory_kit_items (
    id UUID PRIMARY KEY,
    kit_id UUID NOT NULL REFERENCES inventory_kits(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES items(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_inventory_kits_tuntas_status
    ON inventory_kits(tuntas_id, status);

CREATE INDEX IF NOT EXISTS idx_inventory_kit_items_kit
    ON inventory_kit_items(kit_id);

CREATE INDEX IF NOT EXISTS idx_inventory_kit_items_item
    ON inventory_kit_items(item_id);
