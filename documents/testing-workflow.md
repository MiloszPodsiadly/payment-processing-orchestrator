# Testing Workflow

Phase 3 adds Pekko PaymentEntity runtime/persistence tests on top of the Phase 1 and Phase 2 testing platform.

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
- `domain` has unit tests for the Payment protocol ADTs, legal `decide` / `evolve` paths, duplicate-safe no-op semantics, completed refund command replay, refund-failure duplicate semantics, stale operation rejection, aggregate-wide provider operation reuse rejection, replay operation correlation, unknown-outcome safety, financial invariant hardening, full protocol token diagnostic redaction, and corrupt event history failure.
- `domain` has property-based tests for Money precision, core payment immutability, refund bounds, single capture, fraud/decline safety, unknown safety, duplicate mutation intent, decide/evolve consistency, corrupted refund-history mutation rejection, and state-aware generated lifecycle traces with invariants checked after each accepted step.
- `domain` has transition-matrix and ADT inventory tests covering every implemented `PaymentState`, `PaymentCommand`, and `PaymentEvent` case, important illegal commands, wrong provider operation results, duplicate results, out-of-order results, and invalid event history.
- `runtime-pekko` has actor/persistence tests for the Phase 3 PaymentEntity shell using Pekko Persistence TestKit, including persisted event inspection, accepted/rejected/no-op behavior, restart recovery, Pending recovery, Unknown recovery, journal write failure, corrupt history, cross-PaymentId recovery contamination, deterministic persistence IDs, runtime-owned `PaymentEvent` serializer config, compiler-derived serializer fixture inventory, current-version `PaymentEvent` round-trip coverage, malformed serializer payload rejection, and rapid conflicting command serialization.
- `bootstrap` has configuration loader tests covering explicit runtime environment, typed provider mode, missing mandatory fields, invalid values, unsupported production runtime, and unsafe production placeholders.
- `integration-tests` has source boundary checks for forbidden framework imports in `domain` and `application`, including negative fixtures for missing directories and forbidden imports.
- `verifyArchitecture` checks expected repository directories, approved production compile dependencies for `domain` and `application`, approved direct production dependencies for `runtime-pekko`, known-forbidden dependency families as defense in depth, the `runtime-pekko` absence of Cassandra/Tapir/cluster/projection dependencies, the direct sbt project graph, and then runs the architecture source-boundary suite.
- The architecture gate includes a pure negative fixture proving an unapproved external compile dependency fails the approval policy.

Current event serialization round-trip correctness is tested for the current code version.
The runtime serializer binding is owned by `runtime-pekko` production `reference.conf`,
while test `application.conf` owns only Persistence TestKit overrides. This is not a
claim of backward-compatible event schema evolution, rolling-upgrade safety, or production
upcaster coverage.

## Deferred Test Work

- Cassandra Testcontainers tests
- Tapir endpoint contract tests
- Projection idempotency tests
- Failure and resilience tests
