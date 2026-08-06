CREATE TABLE IF NOT EXISTS pull_request_creation_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repo_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    source_branch VARCHAR(255) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NULL,
    preparation_payload LONGTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL,
    github_id BIGINT NULL,
    github_pr_number INT NULL,
    pull_request_id BIGINT NULL,
    last_error TEXT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    modified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pr_creation_task_repo FOREIGN KEY (repo_id) REFERENCES Repository(id),
    CONSTRAINT fk_pr_creation_task_author FOREIGN KEY (author_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS pull_request_creation_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    last_error TEXT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    modified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pull_request_creation_outbox_task UNIQUE (task_id),
    CONSTRAINT fk_pr_creation_outbox_task FOREIGN KEY (task_id)
        REFERENCES pull_request_creation_task(id),
    INDEX idx_pr_creation_outbox_status_next_attempt (status, next_attempt_at),
    INDEX idx_pr_creation_outbox_status_modified_at (status, modified_at)
);

SHOW INDEX FROM pull_request_creation_outbox;
