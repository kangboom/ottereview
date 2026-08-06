-- Webhook delivery IDs are supplied by GitHub in the X-GitHub-Delivery header.
-- The unique key makes duplicate delivery detection atomic at the database boundary.

CREATE TABLE IF NOT EXISTS webhook_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    last_error TEXT NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    modified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_webhook_inbox_delivery_id UNIQUE (delivery_id),
    INDEX idx_webhook_inbox_status_modified_at (status, modified_at)
);

SHOW INDEX FROM webhook_inbox;
