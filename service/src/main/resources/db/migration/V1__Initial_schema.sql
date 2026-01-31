-- Create users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create feature_flags table
CREATE TABLE feature_flags (
    id BIGSERIAL PRIMARY KEY,
    flag_key VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage INTEGER,
    environment VARCHAR(100) NOT NULL DEFAULT 'default',
    default_value BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(flag_key, environment)
);

-- Create audit_logs table
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT,
    action VARCHAR(50) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    user_email VARCHAR(255),
    changes TEXT,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45)
);

-- Create indexes
CREATE INDEX idx_flag_key ON feature_flags(flag_key);
CREATE INDEX idx_environment ON feature_flags(environment);
CREATE INDEX idx_entity_type ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_user ON audit_logs(user_id);
CREATE INDEX idx_timestamp ON audit_logs(timestamp);

INSERT INTO users (username, email, role)
VALUES ('admin', 'admin@yourdomain.com', 'ADMIN');
