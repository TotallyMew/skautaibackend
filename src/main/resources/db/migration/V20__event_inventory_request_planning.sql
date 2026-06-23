ALTER TABLE event_inventory_requests
    ADD COLUMN IF NOT EXISTS due_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS responsible_user_id UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS event_inventory_request_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id UUID NOT NULL REFERENCES event_inventory_requests(id) ON DELETE CASCADE,
    from_provider VARCHAR(20),
    to_provider VARCHAR(20) NOT NULL,
    changed_by_user_id UUID NOT NULL REFERENCES users(id),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (from_provider IS NULL OR from_provider IN ('UNIT', 'UKVEDYS')),
    CHECK (to_provider IN ('UNIT', 'UKVEDYS'))
);

CREATE INDEX IF NOT EXISTS idx_event_inventory_requests_due_responsible
    ON event_inventory_requests(event_id, responsible_user_id, due_at, status);

CREATE INDEX IF NOT EXISTS idx_event_inventory_request_history_request_created
    ON event_inventory_request_history(request_id, created_at DESC);

CREATE TABLE IF NOT EXISTS senior_unit_access_audit (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
    unit_id UUID NOT NULL REFERENCES organizational_units(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(50) NOT NULL,
    access_mode VARCHAR(20) NOT NULL CHECK (access_mode IN ('INTERNAL', 'PUBLIC')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_senior_unit_access_audit_unit_created
    ON senior_unit_access_audit(unit_id, created_at DESC);
