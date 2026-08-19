# Phase 0 Architecture Charter

## Project Charter

Payment Processing Orchestrator is a fault-tolerant backend system for coordinating a payment lifecycle across unreliable boundaries. The project is intentionally scoped as a modular monolith first, with strong domain modelling and explicit failure semantics before framework integration.

The repository should demonstrate correctness under retries, duplicate delivery, provider timeouts, process crashes, authorization checks, and tenant isolation.

## Scope

Initial capabilities are limited to the payment lifecycle needed to prove the core safety model:

- create payment
- run fraud decision flow
- authorize payment
- capture payment
- refund payment
- read payment state and timeline
- process provider callback
- reconcile unknown provider outcome

## Non-Goals

The initial release does not include:

- real card processing
- real banking or Stripe integration
- PCI certification claims
- frontend or mobile client
- Kafka
- Kubernetes
- subscriptions, invoices, chargebacks, or settlement
- machine learning fraud detection

## System Map

The planned system is a single JVM deployable composed of explicit modules:

- `domain`: pure payment model, commands, events, errors, state machine, and invariants
- `application`: use-case coordination and ports
- `runtime-pekko`: typed actors, persistence, recovery, and later sharding
- `adapter-http-tapir`: HTTP API contracts and request/response mapping
- `adapter-cassandra`: journal configuration and read-model persistence
- `adapter-provider`: mock/failure-injecting payment provider adapter
- `adapter-fraud`: deterministic fraud gateway adapter
- `security`: authentication, permissions, RBAC, and tenant isolation
- `observability`: logging, metrics, tracing, and health signals
- `bootstrap`: wiring of runtime modules into the deployable app
- `integration-tests`: cross-module and infrastructure tests

Dependency direction is inward:

```text
domain
  <- application
    <- runtime/adapters/security
      <- bootstrap
```

## Authority Boundaries

Authorization authority belongs in the backend security boundary, not in clients or UI.

Payment state authority belongs to the event-sourced payment aggregate. Read models are derived views and must not become write authority.

Provider outcome authority is external and may be ambiguous. A timeout is not proof of failure.

## Domain Vocabulary

- `Payment`: aggregate representing one logical payment lifecycle.
- `Money`: amount and currency with explicit validation and no floating-point representation.
- `Tenant`: isolation boundary for all reads and mutations.
- `Merchant`: business owner of a payment context.
- `Customer`: payer identity within a tenant boundary.
- `PaymentOperation`: logical operation such as authorization, capture, or refund.
- `ProviderOperation`: external mutation request with deterministic identity.
- `FraudDecision`: approved, rejected, or manual review outcome.
- `IdempotencyKey`: client-supplied replay protection key bound to canonical request payload.
- `CorrelationId`: operational trace identity for diagnostics.
- `AuditContext`: actor, reason, request, and tenant context for privileged actions.

## Critical Invariants

1. A logical payment cannot be captured more than once.
2. Total refunded amount cannot exceed captured amount.
3. Fraud-rejected or declined payments cannot be captured.
4. Repeated commands and duplicate callbacks must not create duplicate external mutations.
5. Tenant and permission checks must be explicit before protected reads or mutations.

## Payment State Machine Outline

Initial conceptual lifecycle:

```text
CREATED
  -> FRAUD_CHECK_PENDING
  -> READY_FOR_AUTHORIZATION
  -> AUTHORIZATION_PENDING
  -> AUTHORIZED
  -> CAPTURE_PENDING
  -> CAPTURED
  -> REFUND_PENDING
  -> PARTIALLY_REFUNDED
  -> REFUNDED
```

Terminal or exception states include:

```text
FRAUD_REJECTED
MANUAL_REVIEW
DECLINED
AUTHORIZATION_UNKNOWN
CAPTURE_FAILED
CAPTURE_UNKNOWN
REFUND_UNKNOWN
```

Unknown outcome states are first-class states and require reconciliation before unsafe retry.

## Failure Model Outline

The system assumes at-least-once delivery and unreliable external boundaries.

Required failure semantics:

- duplicate client requests are resolved through API idempotency
- duplicate provider operations are prevented through deterministic provider operation IDs
- duplicate event or projection handling must be idempotent
- provider timeout produces an unknown outcome unless a definitive result is known
- process crash recovery depends on replaying persisted payment events
- Cassandra unavailability must produce explicit failure or degradation, never false success
- tenant or permission mismatch fails closed

## Contract Boundaries

Contracts to treat as stable once introduced:

- public HTTP API paths and DTOs
- persisted domain events
- provider adapter operation semantics
- permission names and RBAC rules
- Cassandra access patterns and read-model meanings

Contract changes require explicit review and, where applicable, versioning or migration.

## Initial ADR Skeleton

- ADR-001: Use Scala 3
- ADR-002: Use sbt multi-project structure
- ADR-003: Use functional core / imperative shell
- ADR-004: Use hexagonal architecture
- ADR-005: Use Apache Pekko Typed
- ADR-006: Use event sourcing for the payment aggregate
- ADR-007: Use Cassandra as persistence journal
- ADR-008: Use CQRS read models
- ADR-009: Use Pekko Projections for read-side updates
- ADR-010: Use permission-based RBAC
- ADR-011: Treat provider timeout as potentially unknown outcome
- ADR-012: Use explicit API and provider idempotency
- ADR-013: Use modular monolith first
- ADR-014: Do not introduce Kafka initially
- ADR-015: Do not store real payment card credentials
- ADR-016: Use at-least-once processing with idempotent consumers
- ADR-017: Introduce Cluster Sharding only after single-node correctness
- ADR-018: Use Cassandra query-first modelling

## Quality Rules

- Domain code must not depend on Pekko, Tapir, Cassandra, JWT, logging, metrics, Docker, or JSON libraries.
- Business decisions must be deterministic and side-effect free.
- Illegal state transitions must be rejected by the domain model.
- Sensitive data such as raw PAN, CVV, secrets, JWTs, and provider credentials must not be persisted or logged.
- Read models are disposable projections from authoritative events.
- Retry behavior must be explicit, bounded, and safe under unknown provider outcomes.
- Production-readiness claims must be backed by tests, configuration, and observable behavior.

## Phase 0 Gate Answers

`What is Payment?`

A payment is the authoritative event-sourced aggregate for one logical payment lifecycle within a tenant.

`What can Payment do?`

It can validate lifecycle commands, produce durable domain events, coordinate external operation intent, and recover its state from history.

`What can Payment never do?`

It must never double-capture, over-refund, cross tenant boundaries, bypass authorization, silently mutate amount or currency, or treat timeout as definitive failure.

`What is an unknown provider outcome?`

An unknown provider outcome means the external provider may have performed the mutation, but the system lacks a definitive result. It requires reconciliation or provider-idempotent retry semantics.

`What guarantees must survive retries and crashes?`

No duplicate charge, no duplicate refund, no illegal state transition, no tenant escape, no authorization bypass, no sensitive-data persistence, and deterministic recovery from persisted events.

