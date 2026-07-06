ALTER TABLE tuntai
    DROP CONSTRAINT IF EXISTS tuntai_status_check;

ALTER TABLE tuntai
    ADD CONSTRAINT tuntai_status_check
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED', 'DELETED'));
