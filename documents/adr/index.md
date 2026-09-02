# Architecture Decision Records

Initial ADRs are identified during Phase 0 and will be expanded as the implementation introduces concrete trade-offs.

## Accepted Direction

- ADR-001: Use Scala 3
- ADR-002: Use sbt multi-project structure
- ADR-003: Use functional core / imperative shell
- ADR-004: Use hexagonal architecture
- ADR-005: Use Apache Pekko Typed
- ADR-006: Use event sourcing for the payment aggregate
- [ADR-007: Use Cassandra as persistence journal](ADR-007-use-cassandra-as-persistence-journal.md)
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

## Phase 1 Note

This index is not a substitute for full ADRs. It records the accepted decision set so Phase 1 can link to ADRs without claiming that detailed records are complete.

