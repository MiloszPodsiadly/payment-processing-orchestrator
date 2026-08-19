# Testing Workflow

Phase 2 adds pure domain tests on top of the Phase 1 testing platform.

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

- `domain` has primitive value tests for typed IDs, provider operation IDs, payment method tokens, currency metadata, and Money validation.
- `domain` has unit tests for the Payment protocol ADTs, legal `decide` / `evolve` paths, duplicate-safe no-op semantics, stale operation rejection, unknown-outcome safety, financial invariant hardening, and corrupt event history failure.
- `domain` has property-based tests for Money precision, core payment immutability, refund bounds, single capture, fraud/decline safety, unknown safety, duplicate mutation intent, and decide/evolve consistency.
- `domain` has transition-matrix tests covering every implemented `PaymentState`, important illegal commands, wrong provider operation results, duplicate results, out-of-order results, and invalid event history.
- `bootstrap` has configuration loader tests covering explicit runtime environment, typed provider mode, missing mandatory fields, invalid values, unsupported production runtime, and unsafe production placeholders.
- `integration-tests` has source boundary checks for forbidden framework imports in `domain` and `application`, including negative fixtures for missing directories and forbidden imports.
- `verifyArchitecture` checks expected repository directories, approved production compile dependencies for `domain` and `application`, known-forbidden dependency families as defense in depth, the direct sbt project graph, and then runs the architecture source-boundary suite.
- The architecture gate includes a pure negative fixture proving an unapproved external compile dependency fails the approval policy.

## Deferred Test Work

- Pekko actor tests
- Persistence recovery tests
- Cassandra Testcontainers tests
- Tapir endpoint contract tests
- Projection idempotency tests
- Failure and resilience tests
