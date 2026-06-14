ALTER TABLE events
ADD COLUMN IF NOT EXISTS target_audience VARCHAR(100);

ALTER TABLE event_roles
DROP CONSTRAINT IF EXISTS event_roles_target_group_check;

ALTER TABLE event_roles
ADD CONSTRAINT event_roles_target_group_check CHECK (
    target_group IS NULL OR target_group IN (
        'VILKAI',
        'SKAUTAI',
        'PATYRE_SKAUTAI',
        'VYR_SKAUTAI',
        'VYR_SKAUTES',
        'SKAUTAI_VILKAI',
        'TEVAI',
        'PROGRAMA'
    )
);
