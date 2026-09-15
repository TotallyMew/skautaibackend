-- Existing request IDs are retained, but cannot be replayed without a proven payload match.
ALTER TABLE event_inventory_movements ADD COLUMN request_hash VARCHAR(64);
