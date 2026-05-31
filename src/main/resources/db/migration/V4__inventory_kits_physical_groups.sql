ALTER TABLE inventory_kits
    ADD COLUMN IF NOT EXISTS custodian_id UUID REFERENCES organizational_units(id),
    ADD COLUMN IF NOT EXISTS location_id UUID REFERENCES locations(id),
    ADD COLUMN IF NOT EXISTS temporary_storage_label VARCHAR(255),
    ADD COLUMN IF NOT EXISTS responsible_user_id UUID REFERENCES users(id);

CREATE INDEX IF NOT EXISTS idx_inventory_kits_custodian
    ON inventory_kits(custodian_id);

CREATE INDEX IF NOT EXISTS idx_inventory_kits_location
    ON inventory_kits(location_id);

CREATE INDEX IF NOT EXISTS idx_inventory_kits_responsible_user
    ON inventory_kits(responsible_user_id);

DROP INDEX IF EXISTS idx_inventory_kit_items_item;

DELETE FROM inventory_kit_items iki
USING inventory_kits ik
WHERE iki.kit_id = ik.id
  AND ik.status = 'INACTIVE';

DELETE FROM inventory_kit_items iki
USING (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY id) AS rn
    FROM inventory_kit_items
) duplicates
WHERE iki.id = duplicates.id
  AND duplicates.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_kit_items_item
    ON inventory_kit_items(item_id);
