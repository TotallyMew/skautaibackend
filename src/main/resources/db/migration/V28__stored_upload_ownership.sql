-- New uploads only. Legacy files remain readable only through authorized, unambiguous references.
CREATE TABLE stored_uploads (
    id UUID PRIMARY KEY,
    tuntas_id UUID NOT NULL REFERENCES tuntai(id),
    uploader_id UUID REFERENCES users(id) ON DELETE SET NULL,
    file_url TEXT NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('IMAGE', 'DOCUMENT')),
    content_type VARCHAR(100) NOT NULL DEFAULT 'application/octet-stream',
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RECEIVING', 'READY', 'DELETING', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attached_at TIMESTAMPTZ
);
CREATE INDEX idx_stored_uploads_tenant_state ON stored_uploads(tuntas_id, state);
CREATE INDEX idx_stored_uploads_uploader_state ON stored_uploads(uploader_id, state);
CREATE INDEX idx_stored_uploads_cleanup ON stored_uploads(state, created_at) WHERE attached_at IS NULL;

