# Payment Processing Orchestrator

Payment orchestration backend repository built as a Scala 3 portfolio project.

The system is intended to demonstrate safe payment lifecycle coordination under retries,
duplicate delivery, provider timeouts, crashes, authorization boundaries, and tenant isolation.

## Core Direction

- Scala 3 with sbt multi-project layout
- Hexagonal architecture with a pure domain core
- Functional core, imperative shell
- Event-sourced payment aggregate with Apache Pekko
- CQRS read side backed by Cassandra
- Explicit API and provider idempotency
- Permission-based RBAC and tenant isolation
- Failure-aware retry and reconciliation semantics

## Current Status

In progress.

## Quality Bar

The project optimizes for correctness, safety, explicit semantics,
testability, and operability before convenience. In particular,
the final system must prevent duplicate capture/refund behavior,
illegal state transitions, authorization bypass,
tenant boundary violations, and unsafe retries after unknown provider outcomes.
