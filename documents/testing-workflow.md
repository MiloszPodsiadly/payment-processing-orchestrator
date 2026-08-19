# Testing Workflow

Phase 1 provides the testing platform only. Payment behavior tests start in Phase 2.

## Test Categories

- Local fast feedback: `sbt test`
- Full fast-test verification: `sbt testFull`
- Formatting verification: `sbt "scalafmtSbtCheck; scalafmtCheckAll"`
- Static analysis: `sbt "scalafixAll --check"`
- Architecture checks: `sbt verifyArchitecture`
- Integration-test compilation: `sbt integrationTests/Test/compile`
- Full CI-equivalent local check: `sbt "clean; compile; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check; testFull; verifyArchitecture; integrationTests/Test/compile"`

`sbt test` is intentionally kept as normal sbt incremental/cache-aware feedback for
local fast unit/module tests. Mandatory CI uses `sbt testFull` so the complete fast
test suite is physically executed on the current HEAD.

The `integration-tests` project remains explicitly invoked. In Phase 1, CI compiles
integration-test sources through `sbt integrationTests/Test/compile`; it does not execute
future Cassandra/Testcontainers integration suites as part of the fast test workflow.

## Current Test Coverage

- `bootstrap` has configuration loader tests covering explicit runtime environment, typed provider mode, missing mandatory fields, invalid values, unsupported production runtime, and unsafe production placeholders.
- `integration-tests` has source boundary checks for forbidden framework imports in `domain` and `application`, including negative fixtures for missing directories and forbidden imports.
- `verifyArchitecture` checks expected repository directories, approved production compile dependencies for `domain` and `application`, known-forbidden dependency families as defense in depth, the direct sbt project graph, and then runs the architecture source-boundary suite.
- The architecture gate includes a pure negative fixture proving an unapproved external compile dependency fails the approval policy.

## Deferred Test Work

- Payment domain invariant tests
- Property-based transition tests
- Pekko actor tests
- Persistence recovery tests
- Cassandra Testcontainers tests
- Tapir endpoint contract tests
- Projection idempotency tests
- Failure and resilience tests
