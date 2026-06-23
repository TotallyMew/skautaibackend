ALTER TABLE unit_assignments
    ADD COLUMN IF NOT EXISTS is_publicly_visible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_unit_assignments_public_visibility
    ON unit_assignments(organizational_unit_id, is_publicly_visible)
    WHERE left_at IS NULL;
