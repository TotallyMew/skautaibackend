WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, tuntas_id
               ORDER BY assigned_at DESC, id DESC
           ) AS row_number
    FROM user_ranks
)
DELETE FROM user_ranks
WHERE id IN (SELECT id FROM ranked WHERE row_number > 1);

ALTER TABLE user_ranks
    DROP CONSTRAINT IF EXISTS uq_user_ranks_user_tuntas;

ALTER TABLE user_ranks
    ADD CONSTRAINT uq_user_ranks_user_tuntas UNIQUE (user_id, tuntas_id);

ALTER TABLE event_roles
    DROP CONSTRAINT IF EXISTS event_roles_event_id_user_id_role_key;

ALTER TABLE event_roles
    DROP CONSTRAINT IF EXISTS event_roles_event_id_user_id_role_unique;
