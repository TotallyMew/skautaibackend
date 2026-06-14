CREATE INDEX IF NOT EXISTS idx_user_ranks_user_tuntas
ON user_ranks(user_id, tuntas_id);

CREATE INDEX IF NOT EXISTS idx_user_leadership_roles_user_tuntas
ON user_leadership_roles(user_id, tuntas_id);
