CREATE TABLE direct_item_loans (
    id UUID PRIMARY KEY,
    item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
    issued_to_user_id UUID NOT NULL REFERENCES users(id),
    issued_by_user_id UUID NOT NULL REFERENCES users(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    returned_quantity INTEGER NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    issued_at TIMESTAMP NOT NULL,
    returned_at TIMESTAMP NULL,
    due_at TIMESTAMP NULL,
    notes TEXT NULL,
    CONSTRAINT chk_direct_item_loans_returned_quantity CHECK (returned_quantity <= quantity),
    CONSTRAINT chk_direct_item_loans_status CHECK (status IN ('ACTIVE', 'RETURNED'))
);

CREATE INDEX idx_direct_item_loans_item_status ON direct_item_loans(item_id, status);
CREATE INDEX idx_direct_item_loans_tuntas_user ON direct_item_loans(tuntas_id, issued_to_user_id);
