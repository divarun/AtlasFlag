-- Feature 2: Remote Configuration
ALTER TABLE feature_flags ADD COLUMN flag_type VARCHAR(20) NOT NULL DEFAULT 'BOOLEAN';
ALTER TABLE feature_flags ADD COLUMN string_value TEXT;

-- Feature 1: User Attribute Targeting
ALTER TABLE feature_flags ADD COLUMN targeting_rules TEXT;

-- Feature 3: Evaluation Analytics
CREATE TABLE flag_evaluations (
    id BIGSERIAL PRIMARY KEY,
    flag_key VARCHAR(255) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    result BOOLEAN NOT NULL,
    hour_bucket TIMESTAMP NOT NULL,
    count BIGINT NOT NULL DEFAULT 1,
    UNIQUE(flag_key, environment, result, hour_bucket)
);
CREATE INDEX idx_eval_lookup ON flag_evaluations(flag_key, environment, hour_bucket);
