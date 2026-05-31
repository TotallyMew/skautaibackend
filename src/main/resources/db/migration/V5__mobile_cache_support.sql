ALTER TABLE locations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE organizational_units ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE events ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();

CREATE TABLE IF NOT EXISTS sync_tombstones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tuntas_id UUID NOT NULL REFERENCES tuntai(id),
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID NOT NULL,
    deleted_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_tombstones_tuntas_resource_deleted
ON sync_tombstones(tuntas_id, resource_type, deleted_at);
