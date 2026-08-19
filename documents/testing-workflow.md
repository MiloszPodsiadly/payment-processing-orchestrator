# Testing Workflow

Phase 1 provides the testing platform only. Payment behavior tests start in Phase 2.

## Test Categories

- Fast unit/module tests: `sbt test`
- Formatting verification: `sbt scalafmtSbtCheck scalafmtCheckAll`
- Static analysis: `sbt "scalafixAll --check"`
- Integration-test compilation: `sbt integrationTests/Test/compile`
- Full CI-equivalent local check: `sbt clean scalafmtSbtCheck scalafmtCheckAll "scalafixAll --check" test integrationTests/Test/compile`

## Current Test Coverage

- `bootstrap` has configuration loader tests covering valid config, missing mandatory fields, invalid values, and unsafe production placeholders.
- `integration-tests` has a module boundary scan for forbidden framework imports in `domain` and `application`.

## Deferred Test Work

- Payment domain invariant tests
- Property-based transition tests
- Pekko actor tests
- Persistence recovery tests
- Cassandra Testcontainers tests
- Tapir endpoint contract tests
- Projection idempotency tests
- Failure and resilience tests

