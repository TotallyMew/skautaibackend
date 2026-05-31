CREATE TABLE IF NOT EXISTS leadership_change_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
    requester_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_assignment_id UUID NOT NULL REFERENCES user_leadership_roles(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    organizational_unit_id UUID NOT NULL REFERENCES organizational_units(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    reason TEXT,
    reviewed_by_user_id UUID REFERENCES users(id),
    successor_user_id UUID REFERENCES users(id),
    review_note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    resolved_assignment_id UUID REFERENCES user_leadership_roles(id)
);

CREATE INDEX IF NOT EXISTS idx_leadership_change_requests_tuntas_status
    ON leadership_change_requests(tuntas_id, status);

CREATE INDEX IF NOT EXISTS idx_leadership_change_requests_assignment_pending
    ON leadership_change_requests(role_assignment_id)
    WHERE status = 'PENDING';
