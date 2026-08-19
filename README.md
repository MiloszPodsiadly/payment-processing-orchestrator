# Payment Processing Orchestrator

Payment orchestration backend repository built as a Scala 3 portfolio project.

The system is intended to demonstrate safe payment lifecycle coordination under retries,
duplicate delivery, provider timeouts, crashes, authorization boundaries, and tenant isolation.

## Current Phase

PHASE 1 - Repository Bootstrap

This repository currently contains the technical foundation only. Payment business behavior is planned but not implemented yet.

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

## Implemented In This Phase

- sbt multi-project structure is defined.
- Module dependency direction is encoded in the sbt graph and checked by `verifyArchitecture`.
- `domain` and `application` production compile dependencies are restricted to an explicit approved set.
- Formatting and static-analysis tasks are configured.
- Typed startup configuration loading exists in `bootstrap`.
- Configuration tests validate explicit runtime environment, typed provider mode, mandatory fields, invalid values, and unsupported production runtime.
- Local Cassandra infrastructure is defined in `compose.yaml`.
- CI workflow is configured to run compile, formatting, lint, fast tests, architecture checks, and integration-test compilation.
- Direct GitHub Actions are pinned to immutable commit SHAs.

## Verified Locally

- Full sbt gate passed with a local sbt 2.0.6 runner before PPO-1 remediation. Re-run the current commands below after changing the build.
- `docker compose config` parses the Cassandra Compose file.
- `docker compose up -d cassandra` starts Cassandra and the container reaches `healthy`.
- Windows and macOS/Linux environment wrapper scripts create `.env` from `.env.example` when missing and load it only for the child process they run.
- Static boundary search, expected directory checks, approved compile dependency checks, known-forbidden dependency checks, and project graph checks are exposed through `verifyArchitecture`.

## What Does Not Exist Yet

- Payment domain model
- Payment state machine implementation
- Payment commands and events
- Pekko payment entity
- Cassandra journal integration
- Tapir payment API
- JWT and RBAC implementation
- Fraud or provider integrations
- API/provider idempotency behavior
- Production-ready runtime

## Prerequisites

- JDK 21
- sbt 2.0.6 runner
- Docker with Docker Compose

## Commands

```powershell
sbt "clean; compile"
sbt test
sbt "scalafmtSbtCheck; scalafmtCheckAll"
sbt "scalafixAll --check"
sbt verifyArchitecture
sbt integrationTests/Test/compile
```

Full local CI-equivalent check:

```powershell
sbt "clean; compile; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check; test; verifyArchitecture; integrationTests/Test/compile"
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
docker compose ps
docker compose logs cassandra
docker compose stop cassandra
docker compose down -v
```

`docker compose down -v` deletes the local Cassandra volume.

If IntelliJ shows unresolved test dependencies after build changes, regenerate or reload
the sbt/BSP model as described in [Local Development](documents/local-development.md).

## Module Overview

- `modules/domain`: pure domain model in Phase 2; no framework dependencies.
- `modules/application`: use-case coordination and ports.
- `modules/runtime-pekko`: typed actor and persistence runtime when introduced.
- `modules/adapter-http-tapir`: HTTP API adapter when introduced.
- `modules/adapter-cassandra`: Cassandra adapter when introduced.
- `modules/adapter-provider`: payment provider adapter when introduced.
- `modules/adapter-fraud`: fraud gateway adapter when introduced.
- `modules/security`: authentication, permissions, RBAC, and tenant isolation when introduced.
- `modules/observability`: runtime logging, metrics, tracing, and health support when introduced.
- `modules/bootstrap`: composition root and typed configuration boundary.
- `integration-tests`: cross-module and infrastructure tests.

## Project Documents

- [Phase 0 Architecture Charter](documents/phase-0-architecture-charter.md)
- [Module Dependency Map](documents/module-dependency-map.md)
- [Configuration](documents/configuration.md)
- [Testing Workflow](documents/testing-workflow.md)
- [Local Development](documents/local-development.md)
- [ADR Index](documents/adr/index.md)

## Quality Bar

The project optimizes for correctness, safety, explicit semantics,
testability, and operability before convenience. In particular,
the final system must prevent duplicate capture/refund behavior,
illegal state transitions, authorization bypass,
tenant boundary violations, and unsafe retries after unknown provider outcomes.
