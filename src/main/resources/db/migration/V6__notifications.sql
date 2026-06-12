CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tuntas_id UUID NULL REFERENCES tuntai(id),
    title VARCHAR(120) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    resource VARCHAR(80) NULL,
    entity_id UUID NULL,
    data TEXT NULL,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_created
ON notifications(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_user_read_created
ON notifications(user_id, read_at, created_at DESC);
