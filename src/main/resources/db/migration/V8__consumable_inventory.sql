ALTER TABLE items
    ADD COLUMN IF NOT EXISTS is_consumable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS unit_of_measure VARCHAR(30) NOT NULL DEFAULT 'vnt.',
    ADD COLUMN IF NOT EXISTS minimum_quantity INTEGER NULL;

ALTER TABLE items
    DROP CONSTRAINT IF EXISTS items_quantity_check;

ALTER TABLE items
    ADD CONSTRAINT items_quantity_check CHECK (quantity >= 0);

ALTER TABLE items
    ADD CONSTRAINT items_minimum_quantity_check CHECK (minimum_quantity IS NULL OR minimum_quantity >= 0);
