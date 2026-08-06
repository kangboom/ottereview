# Manual database migrations

The project currently relies on Hibernate `ddl-auto=update` and does not have a
schema migration runner such as Flyway or Liquibase. SQL files in this directory
are therefore reviewed, executed once per environment, and recorded by the
deployment operator.

Before running a migration:

1. Back up the target database.
2. Run the duplicate-detection queries at the top of the file.
3. Resolve duplicates explicitly; do not delete business rows automatically.
4. Apply the DDL during a maintenance window.
5. Verify the resulting indexes with `SHOW INDEX`.

`V001__correct_pull_request_unique_constraints.sql` replaces the incorrect
global uniqueness of `github_pr_number` with these domain invariants:

- GitHub node ID uniquely identifies a pull request globally.
- Pull request number is unique within a repository.
- A user can be registered as a reviewer only once per pull request.

`V002__create_webhook_inbox.sql` records GitHub deliveries before processing:

- `delivery_id` is unique, so the same delivery is not applied twice.
- processing status and retry count make failures observable and retryable.
- the status/modified-time index supports later reconciliation of stale rows.

`V003__add_webhook_inbox_recovery_fields.sql` enables automatic recovery:

- `next_retry_at` schedules failed deliveries with backoff instead of a tight retry loop.
- the status/retry-time index supports bounded polling by the recovery worker.
