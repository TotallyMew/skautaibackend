CREATE INDEX IF NOT EXISTS idx_items_duplicate_lookup
ON items(tuntas_id, status, type, category, custodian_id, lower(name), updated_at);
