# ADR-007: Use Cassandra As Persistence Journal

## Status

Accepted for Phase 4.

## Context

The payment aggregate is event sourced and must survive actor system restarts without
changing domain semantics. Phase 3 proved recovery with Persistence TestKit. Phase 4
requires a real durable journal while keeping the domain, application, and runtime entity
free from Cassandra ownership.

The long-term architecture includes CQRS/read-model work, but Phase 4 needs only the
append-oriented write journal. The Phase 4 Cassandra tables are Pekko plugin internal
journal tables, not hand-designed application read models.

## Decision

Use Apache Pekko Persistence Cassandra as the production journal plugin for
`PaymentEntity` event persistence.

The Cassandra adapter owns the plugin dependency, schema migration resource, and startup
validation. Bootstrap selects `pekko.persistence.cassandra.journal` and provides
environment-specific contact point and local datacenter settings. The journal keyspace is
canonical: `pekko`.

The decision uses Cassandra here because the payment aggregate already has an
event-sourced, append-oriented persistence model and must recover acknowledged events
after process crashes. Cassandra also aligns with the planned Cassandra-backed CQRS
direction while keeping Phase 4 limited to the write journal.

Phase 4 uses:

- explicit versioned schema migration
- schema autocreate disabled
- `QUORUM` request consistency
- `target-partition-size = 500000` as a compatibility contract
- journal deletes disabled
- no snapshot schema

## Consequences

- `PaymentEntity` keeps the same persistence id and event-sourced behavior.
- `PaymentEventSerializer` remains runtime-owned and is the serializer recorded in journal
  rows for payment events.
- Cassandra schema is created explicitly by migration, not by runtime autocreate.
- Missing Cassandra infrastructure fails closed during validation.
- CQRS read models, projections, sharding, and snapshots remain deferred.

## Failure Semantics

- Cassandra unavailable at startup returns an explicit unavailable validation error.
- Missing keyspace, missing table, or structurally incompatible table returns an explicit
  schema validation error.
- Runtime autocreate remains disabled, so startup cannot silently create schema.
- A journal write failure must not produce `PaymentEntity.Accepted`.
- Corrupt history or unreadable serialized bytes fail recovery instead of fabricating state.
- No custom retry loop, repair flow, or production upcaster is implemented in Phase 4.

## Schema Ownership

`adapter-cassandra` owns the versioned CQL migration and startup validation. The runtime
entity does not execute Cassandra CQL and does not import Cassandra types.

## Consistency Decision

The journal uses `QUORUM` request consistency. Phase 4 prioritizes acknowledged event
durability evidence over the lower-latency local-only path. This is still local topology
evidence, not a production HA claim.

## Partition-Size Decision

The configured `target-partition-size` stays at `500000`, the plugin default, and Phase 4
startup validation requires that exact value. Once journal data exists, this setting is
part of the persisted-journal partitioning contract rather than casual runtime tuning.
Changing it requires explicit migration or compatibility design and evidence.

## Migration Decision

Schema is created through a versioned migration file and a local one-shot Compose
migration service. Integration tests load the same migration resource. The plugin's
development autocreate mode is rejected for runtime startup.

## Snapshot Deferral

Snapshots remain out of scope. No snapshot store schema is created by Phase 4.

## Testing Evidence

Phase 4 evidence includes Persistence TestKit runtime tests for actor semantics and real
Cassandra Testcontainers tests for durable backend integration, full `PaymentRuntime`
restart recovery, cross-ActorSystem recovery, deterministic outage-after-recovery write
failure, structural schema validation attacks, journal row serializer metadata, and static
v1 serializer read/write compatibility fixtures.

## Rejected Alternatives

- In-memory or local file journal: insufficient durability for Phase 4 recovery checks.
- Runtime plugin autocreate: convenient locally but unsafe as a production startup path.
- Moving Cassandra code into `runtime-pekko`: violates the runtime boundary; the entity
  should know persistence abstractions, not Cassandra implementation details.
- Application-owned read-model tables now: rejected because CQRS/read-side work belongs
  to later phases.
