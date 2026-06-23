ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMP;

CREATE TABLE account_deletion_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    requested_via VARCHAR(20) NOT NULL CHECK (requested_via IN ('APP', 'WEB')),
    expires_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_deletion_requests_user
    ON account_deletion_requests(user_id, created_at DESC);

CREATE INDEX idx_account_deletion_requests_expiry
    ON account_deletion_requests(expires_at);
