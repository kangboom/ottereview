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
