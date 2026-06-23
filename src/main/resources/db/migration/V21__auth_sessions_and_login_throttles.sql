CREATE TABLE auth_refresh_sessions (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('user', 'super_admin')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    replaced_by_session_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP
);

CREATE INDEX idx_auth_refresh_sessions_subject_active
    ON auth_refresh_sessions(subject_id, subject_type, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_auth_refresh_sessions_expiry
    ON auth_refresh_sessions(expires_at);

CREATE TABLE auth_login_throttles (
    key VARCHAR(320) PRIMARY KEY,
    failed_count INTEGER NOT NULL DEFAULT 0,
    window_started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    blocked_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_auth_login_throttles_cleanup
    ON auth_login_throttles(updated_at, blocked_until);
