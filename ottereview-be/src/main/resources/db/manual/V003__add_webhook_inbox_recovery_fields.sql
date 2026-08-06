-- Run once after V002__create_webhook_inbox.sql.
-- next_retry_at prevents failed deliveries from being retried in a tight loop.

ALTER TABLE webhook_inbox
    ADD COLUMN next_retry_at DATETIME(6) NULL AFTER processed_at,
    ADD INDEX idx_webhook_inbox_status_next_retry_at (status, next_retry_at);

SHOW INDEX FROM webhook_inbox;
