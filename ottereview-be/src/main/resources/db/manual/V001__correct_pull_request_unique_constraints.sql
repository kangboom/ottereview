-- Run once against an existing MySQL schema before deploying the matching entity changes.
-- The ALTER statements intentionally fail when duplicate or NULL identifiers exist.
-- Resolve those rows explicitly instead of deleting business data automatically.

SELECT github_id, COUNT(*) AS duplicate_count
FROM pull_request
WHERE github_id IS NOT NULL
GROUP BY github_id
HAVING COUNT(*) > 1;

SELECT repo_id, github_pr_number, COUNT(*) AS duplicate_count
FROM pull_request
WHERE repo_id IS NOT NULL
  AND github_pr_number IS NOT NULL
GROUP BY repo_id, github_pr_number
HAVING COUNT(*) > 1;

SELECT pull_request_id, user_id, COUNT(*) AS duplicate_count
FROM reviewer
GROUP BY pull_request_id, user_id
HAVING COUNT(*) > 1;

-- A PR number is unique only inside a repository. Find and remove the legacy
-- single-column unique index without relying on Hibernate's generated name.
SET @legacy_pr_number_index = (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'pull_request'
      AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 1
       AND SUM(column_name = 'github_pr_number') = 1
    LIMIT 1
);

SET @drop_legacy_index_sql = IF(
    @legacy_pr_number_index IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE pull_request DROP INDEX `', @legacy_pr_number_index, '`')
);
PREPARE drop_legacy_index_statement FROM @drop_legacy_index_sql;
EXECUTE drop_legacy_index_statement;
DEALLOCATE PREPARE drop_legacy_index_statement;

ALTER TABLE pull_request
    MODIFY github_id BIGINT NOT NULL,
    MODIFY github_pr_number INT NOT NULL,
    MODIFY repo_id BIGINT NOT NULL;

SET @add_github_id_constraint_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'pull_request'
          AND constraint_name = 'uk_pull_request_github_id'
    ),
    'SELECT 1',
    'ALTER TABLE pull_request ADD CONSTRAINT uk_pull_request_github_id UNIQUE (github_id)'
);
PREPARE add_github_id_constraint_statement FROM @add_github_id_constraint_sql;
EXECUTE add_github_id_constraint_statement;
DEALLOCATE PREPARE add_github_id_constraint_statement;

SET @add_repo_number_constraint_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'pull_request'
          AND constraint_name = 'uk_pull_request_repo_number'
    ),
    'SELECT 1',
    'ALTER TABLE pull_request ADD CONSTRAINT uk_pull_request_repo_number UNIQUE (repo_id, github_pr_number)'
);
PREPARE add_repo_number_constraint_statement FROM @add_repo_number_constraint_sql;
EXECUTE add_repo_number_constraint_statement;
DEALLOCATE PREPARE add_repo_number_constraint_statement;

SET @add_reviewer_constraint_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'reviewer'
          AND constraint_name = 'uk_reviewer_pull_request_user'
    ),
    'SELECT 1',
    'ALTER TABLE reviewer ADD CONSTRAINT uk_reviewer_pull_request_user UNIQUE (pull_request_id, user_id)'
);
PREPARE add_reviewer_constraint_statement FROM @add_reviewer_constraint_sql;
EXECUTE add_reviewer_constraint_statement;
DEALLOCATE PREPARE add_reviewer_constraint_statement;
