# Payment Processing Orchestrator

Payment orchestration backend repository built as a Scala 3 portfolio project.

The system is intended to demonstrate safe payment lifecycle coordination under retries,
duplicate delivery, provider timeouts, crashes, authorization boundaries, and tenant isolation.

## Current Phase

PHASE 4 - Cassandra Persistence

This repository currently contains the Phase 1 technical foundation, the pure Phase 2
payment domain, the Phase 3 Apache Pekko Typed event-sourced payment entity, and the
Phase 4 Cassandra journal persistence integration.

## Core Direction

- Scala 3 with sbt multi-project layout
- Hexagonal architecture with a pure domain core
- Functional core, imperative shell
- Event-sourced payment aggregate with Apache Pekko
- CQRS read side backed by Cassandra
- Explicit API and provider idempotency
- Permission-based RBAC and tenant isolation
- Failure-aware retry and reconciliation semantics

## Current Stack

- Scala 3.8.4
- sbt 2.0.6
- JDK 21 target
- MUnit and ScalaCheck for testing
- Scalafmt for deterministic formatting
- Scalafix plus compiler warnings for static checks
- Typesafe Config for typed startup configuration loading
- Docker Compose with local Cassandra 5.0.8
- Apache Pekko Typed and Pekko Persistence Typed in `runtime-pekko`
- Apache Pekko Persistence Cassandra in `adapter-cassandra`

## Implemented In This Phase

- sbt multi-project structure is defined.
- Module dependency direction is encoded in the sbt graph and checked by `verifyArchitecture`.
- `domain` and `application` production compile dependencies are restricted to an explicit approved set.
- Formatting and static-analysis tasks are configured.
- Typed startup configuration loading exists in `bootstrap`.
- Configuration tests validate explicit runtime environment, typed provider mode, mandatory fields, invalid values, and unsupported production runtime.
- Pure domain identifiers, Money, Currency, Payment core data, state, commands, events, errors, `decide`, and `evolve` exist in `modules/domain`.
- Domain rules cover fraud, authorization, capture, refund, duplicate-safe replay, completed refund idempotency, stale provider results, aggregate-wide provider operation uniqueness, provider operation correlation, unknown outcome states, replay integrity, full protocol token diagnostic redaction, and refund bounds.
- `runtime-pekko` contains a typed `PaymentEntity` implemented with `EventSourcedBehavior.withEnforcedReplies`.
- `PaymentEntity` uses `PaymentDecider.decide` as the only command authority and `PaymentDecider.evolve` as the only business event transition authority.
- Accepted mutation replies are emitted only after persistence; domain rejections and duplicate-safe `Right(Nil)` decisions persist zero events.
- The entity uses deterministic `PersistenceId` values in the form `payment|<payment-id>`.
- Runtime envelope and recovery checks bind one entity/journal to one `PaymentId`; cross-payment creation contamination fails loudly.
- Phase 3 tests prove current-version `PaymentEvent` serialization round trips, inherit the runtime-owned serializer binding from `runtime-pekko` `reference.conf`, and run Persistence TestKit with event serialization enabled.
- Recovery tests cover Created, Pending, Authorized/Captured/Refunded, Unknown, partial refund, corrupt history, journal write failure, and mailbox serialization scenarios.
- `adapter-cassandra` owns the Cassandra journal schema migration and startup validation.
- Bootstrap selects `pekko.persistence.cassandra.journal`, disables keyspace/table autocreate, disables deletes, and requires QUORUM requests.
- Cassandra Testcontainers integration tests cover schema migration, startup validation, cross-ActorSystem recovery, pending/unknown recovery, aggregate isolation, journal row serializer metadata, static v1 serializer fixtures, and missing-schema fail-closed behavior.
- Local Cassandra infrastructure and migration execution are defined in `compose.yaml`.
- CI workflow is configured to run compile, formatting, lint, fast tests, architecture checks, and Cassandra integration tests.
- Direct GitHub Actions are pinned to immutable commit SHAs.

## Verified Locally

- Full sbt gate passed with a local sbt 2.0.6 runner before PPO-1 remediation. Re-run the current commands below after changing the build.
- `docker compose config` parses the Cassandra Compose file.
- `docker compose up -d cassandra` starts Cassandra and the container reaches `healthy`.
- Windows and macOS/Linux environment wrapper scripts create `.env` from `.env.example` when missing and load it only for the child process they run.
- Static boundary search, expected directory checks, approved compile dependency checks, known-forbidden dependency checks, and project graph checks are exposed through `verifyArchitecture`.
- Domain unit, property-based, hardening, corrupted-history, ADT inventory, state-aware trace, transition-matrix, and Phase 3 runtime persistence tests run through `sbt testFull`.

## What Does Not Exist Yet

- Tapir payment API
- JWT and RBAC implementation
- Fraud or provider integrations
- API/provider idempotency behavior
- Cluster Sharding or distributed single-writer enforcement
- Pekko Projections
- Snapshots
- Provider side effects and reconciliation workers
- Production event schema migration/upcasters
- Production-ready runtime

## Prerequisites

- JDK 21
- sbt 2.0.6 runner
- Docker with Docker Compose

## Commands

```powershell
sbt "clean; compile"
sbt "domain/Test/testFull"
sbt test
sbt testFull
sbt "scalafmtSbtCheck; scalafmtCheckAll"
sbt "scalafixAll --check"
sbt verifyArchitecture
sbt integrationTests/Test/testFull
```

`sbt test` is the local fast feedback command and may use normal sbt
incremental/cache-aware behavior. `sbt testFull` runs the complete fast test suite and is
the mandatory CI fast-test command. Integration tests remain separate and Phase 4 CI runs
them with `sbt integrationTests/Test/testFull`.

Full local CI-equivalent check:

```powershell
sbt "clean; compile; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check; testFull; verifyArchitecture; integrationTests/Test/testFull"
```

The JVM does not automatically read `.env`. Either export `PAYMENT_*` variables in the
shell before launching sbt/application code, or run commands through the local wrappers.
The wrappers create `.env` from `.env.example` only when `.env` is missing.
They require an explicit child command and never dump loaded environment values by default.

Windows:

```powershell
.\scripts\windows\run-with-env.ps1 -- sbt "bootstrap/Test/runMain com.paymentprocessing.bootstrap.config.AppConfigEnvironmentProbe"
```

macOS/Linux:

```sh
sh scripts/unix/run-with-env.sh -- sbt "bootstrap/Test/runMain com.paymentprocessing.bootstrap.config.AppConfigEnvironmentProbe"
```

Local Cassandra:

```powershell
docker compose up -d cassandra
docker compose up -d cassandra-migrate
docker compose ps
docker compose logs cassandra
docker compose logs cassandra-migrate
docker compose stop cassandra
docker compose down -v
```

`docker compose down -v` deletes the local Cassandra volume.

If IntelliJ shows unresolved test dependencies after build changes, regenerate or reload
the sbt/BSP model as described in [Local Development](documents/local-development.md).

## Module Overview

- `modules/domain`: pure domain model in Phase 2; no framework dependencies.
- `modules/application`: use-case coordination and ports.
- `modules/runtime-pekko`: typed `PaymentEntity`, event-sourced behavior, runtime-owned current-version event serialization, and Phase 3 recovery tests.
- `modules/adapter-http-tapir`: HTTP API adapter when introduced.
- `modules/adapter-cassandra`: Cassandra journal schema, Pekko Persistence Cassandra dependency, and startup validation.
- `modules/adapter-provider`: payment provider adapter when introduced.
- `modules/adapter-fraud`: fraud gateway adapter when introduced.
- `modules/security`: authentication, permissions, RBAC, and tenant isolation when introduced.
- `modules/observability`: runtime logging, metrics, tracing, and health support when introduced.
- `modules/bootstrap`: composition root and typed configuration boundary.
- `integration-tests`: cross-module and infrastructure tests.

## Project Documents

- [Phase 0 Architecture Charter](documents/phase-0-architecture-charter.md)
- [Module Dependency Map](documents/module-dependency-map.md)
- [Domain Model](documents/domain-model.md)
- [Payment State Machine](documents/payment-state-machine.md)
- [Configuration](documents/configuration.md)
- [Runtime Pekko Payment Entity](documents/runtime-pekko-payment-entity.md)
- [Cassandra Persistence Journal](documents/cassandra-persistence.md)
- [Testing Workflow](documents/testing-workflow.md)
- [Local Development](documents/local-development.md)
- [ADR Index](documents/adr/index.md)

## Quality Bar

The project optimizes for correctness, safety, explicit semantics,
testability, and operability before convenience. In particular,
the final system must prevent duplicate capture/refund behavior,
illegal state transitions, authorization bypass,
tenant boundary violations, and unsafe retries after unknown provider outcomes.
