CREATE INDEX IF NOT EXISTS idx_items_source_shared_status
ON items(source_shared_item_id, status);

CREATE INDEX IF NOT EXISTS idx_item_custom_fields_item_field
ON item_custom_fields(item_id, field_name);

CREATE INDEX IF NOT EXISTS idx_inventory_kit_items_item
ON inventory_kit_items(item_id);

CREATE INDEX IF NOT EXISTS idx_inventory_kits_status
ON inventory_kits(status);
