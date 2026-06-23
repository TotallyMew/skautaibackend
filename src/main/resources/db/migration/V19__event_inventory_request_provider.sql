ALTER TABLE event_inventory_requests
    ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'UKVEDYS';

ALTER TABLE event_inventory_requests
    DROP CONSTRAINT IF EXISTS event_inventory_requests_provider_check;

ALTER TABLE event_inventory_requests
    ADD CONSTRAINT event_inventory_requests_provider_check
    CHECK (provider IN ('UNIT', 'UKVEDYS'));

CREATE INDEX IF NOT EXISTS idx_event_inventory_requests_provider_status
    ON event_inventory_requests(event_id, provider, status, created_at);
