# Testing Workflow

Phase 1 provides the testing platform only. Payment behavior tests start in Phase 2.

## Test Categories

- Fast unit/module tests: `sbt test`
- Formatting verification: `sbt "scalafmtSbtCheck; scalafmtCheckAll"`
- Static analysis: `sbt "scalafixAll --check"`
- Architecture checks: `sbt verifyArchitecture`
- Integration-test compilation: `sbt integrationTests/Test/compile`
- Full CI-equivalent local check: `sbt "clean; compile; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check; test; verifyArchitecture; integrationTests/Test/compile"`

`sbt test` is intentionally reserved for fast unit/module tests. The `integration-tests`
project is executed through explicit commands so future Cassandra/Testcontainers tests do
not become an accidental dependency of the default fast test workflow.

## Current Test Coverage

- `bootstrap` has configuration loader tests covering explicit runtime environment, typed provider mode, missing mandatory fields, invalid values, unsupported production runtime, and unsafe production placeholders.
- `integration-tests` has source boundary checks for forbidden framework imports in `domain` and `application`, including negative fixtures for missing directories and forbidden imports.
- `verifyArchitecture` checks expected repository directories, forbidden compile dependencies for `domain` and `application`, the direct sbt project graph, and then runs the architecture source-boundary suite.

## Deferred Test Work

- Payment domain invariant tests
- Property-based transition tests
- Pekko actor tests
- Persistence recovery tests
- Cassandra Testcontainers tests
- Tapir endpoint contract tests
- Projection idempotency tests
- Failure and resilience tests
