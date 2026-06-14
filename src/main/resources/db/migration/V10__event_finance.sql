ALTER TABLE events
ADD COLUMN IF NOT EXISTS inventory_budget_amount DECIMAL(10,2);

ALTER TABLE event_roles
DROP CONSTRAINT IF EXISTS event_roles_role_check;

ALTER TABLE event_roles
ADD CONSTRAINT event_roles_role_check CHECK (role IN (
    'VIRSININKAS',
    'KOMENDANTAS',
    'UKVEDYS',
    'FINANSININKAS',
    'PASTOVYKLES_GURU',
    'VADOVAS',
    'SAVANORIS',
    'PATYRE_SKAUTAS',
    'SKAUTAS',
    'PROGRAMERIS',
    'MAISTININKAS'
));

CREATE TABLE IF NOT EXISTS event_extra_costs (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    category VARCHAR(40) NOT NULL,
    label VARCHAR(200) NOT NULL,
    quantity DECIMAL(10,2),
    unit VARCHAR(40),
    unit_price DECIMAL(10,2),
    total_amount DECIMAL(10,2) NOT NULL,
    notes TEXT,
    created_by_user_id UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_event_extra_costs_event
ON event_extra_costs(event_id);
