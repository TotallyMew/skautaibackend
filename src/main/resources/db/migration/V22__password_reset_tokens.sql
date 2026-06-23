CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('user', 'super_admin')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_password_reset_tokens_subject
    ON password_reset_tokens(subject_id, subject_type, created_at DESC);

CREATE INDEX idx_password_reset_tokens_expiry
    ON password_reset_tokens(expires_at);
