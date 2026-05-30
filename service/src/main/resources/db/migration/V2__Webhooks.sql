CREATE TABLE webhooks (
    id BIGSERIAL PRIMARY KEY,
    url VARCHAR(2048) NOT NULL,
    secret VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhooks_enabled ON webhooks(enabled);
