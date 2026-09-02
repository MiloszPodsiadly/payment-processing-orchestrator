# Cassandra Persistence Journal

Phase 4 introduces Cassandra as the durable Pekko Persistence journal for `PaymentEntity`.
This is journal persistence only. It does not introduce CQRS read models, projections,
snapshots, sharding, provider side effects, or API idempotency behavior.

## Ownership

- `runtime-pekko` owns the `PaymentEntity`, persistence id format, recovery behavior, and
  current-version `PaymentEvent` serializer.
- `adapter-cassandra` owns Pekko Persistence Cassandra dependencies, journal schema
  resources, and startup validation.
- `bootstrap` selects the Cassandra journal plugin and maps typed payment Cassandra
  settings into Pekko/DataStax configuration.
- `integration-tests` owns real Cassandra Testcontainers coverage.

`domain` and `application` remain Cassandra-free. `runtime-pekko` also remains
Cassandra-free; it only depends on Pekko Typed and Pekko Persistence Typed.

## Plugin And Version

- Pekko Persistence Cassandra: `org.apache.pekko %% pekko-persistence-cassandra % 1.1.0`
- Pekko Connectors Cassandra: `org.apache.pekko %% pekko-connectors-cassandra % 1.1.0`
- Pekko alignment dependencies: `pekko-cluster`, `pekko-cluster-tools`, and
  `pekko-coordination` pinned to the project Pekko version `1.6.0`
- Testcontainers Cassandra: `org.testcontainers % testcontainers-cassandra % 2.0.5`
- Local and integration-test Cassandra image: `cassandra:5.0.8`

The Cassandra dependency is owned by `adapter-cassandra`. The alignment dependencies are
explicit because the pinned Cassandra plugin brings older Pekko cluster artifacts
transitively; ActorSystem startup rejects mixed Pekko versions. Testcontainers dependencies
are test-only and owned by `integration-tests`.

## Persistence Flow

1. A typed `PaymentEntity` receives `PaymentEntity.Execute`.
2. `PaymentDecider.decide` produces either a rejection, a duplicate-safe no-op, or events.
3. `EventSourcedBehavior.withEnforcedReplies` persists events before emitting
   `PaymentEntity.Accepted`.
4. Pekko Persistence routes event writes to `pekko.persistence.cassandra.journal`.
5. The runtime-owned `PaymentEventSerializer` writes payment events into Cassandra journal
   rows with identifier `55032031` and manifest `payment-event-v1`.
6. After ActorSystem termination, a new ActorSystem with the same journal config replays the
   same `PersistenceId` and reconstructs state through `PaymentDecider.evolve`.

## Schema

The journal schema is stored at:

`modules/adapter-cassandra/src/main/resources/db/cassandra/migrations/V001__pekko_persistence_journal.cql`

The migration creates the `pekko` keyspace and the Pekko Persistence Cassandra journal
tables required by the selected plugin:

- `messages`
- `tag_views`
- `tag_write_progress`
- `tag_scanning`
- `metadata`
- `all_persistence_ids`

The migration deliberately does not create snapshot tables. Snapshots are out of scope
for the current phase.

Schema creation is explicit and external to the plugin. Runtime autocreate flags are set
to false so missing keyspaces or tables fail startup validation instead of being silently
created by the application process.

The Phase 4 tables are Pekko plugin internal journal tables. They are not application
read-model tables and are not query-first CQRS tables. Application Cassandra tables begin
with later read-model work.

## Runtime Settings

The selected plugin is:

```hocon
pekko.persistence.journal.plugin = "pekko.persistence.cassandra.journal"
```

The Phase 4 runtime settings keep:

- `pekko.persistence.cassandra.journal.keyspace-autocreate = false`
- `pekko.persistence.cassandra.journal.tables-autocreate = false`
- `pekko.persistence.cassandra.journal.support-deletes = off`
- `pekko.persistence.cassandra.coordinated-shutdown-on-error = on`
- DataStax `advanced.reconnect-on-init = true`
- request consistency `QUORUM`

`target-partition-size` is explicitly set to `500000`. Payment event streams are expected
to be short and payment-specific; this value preserves the plugin default until production
cardinality and event-size measurements justify a deliberate partitioning change.

Deletes are disabled with `support-deletes = off`. Payment event history is append-only in
this phase; GDPR/retention workflows are not implemented.

## Migration Strategy

Local Compose includes a one-shot `cassandra-migrate` service that waits for the Cassandra
container healthcheck and then runs the versioned CQL file with `cqlsh`. The same resource
is loaded by integration tests to prepare Testcontainers Cassandra. The migration uses
`IF NOT EXISTS` so rerunning it against the same clean or existing local volume is safe.

No `ALLOW FILTERING` query is used by application code. Integration tests only inspect
small test-owned journal tables to verify evidence.

## Startup Validation

`CassandraPersistenceStartupValidator` validates configuration before accepting Cassandra
journal readiness. It checks:

- the selected journal plugin is Cassandra
- contact points and local datacenter are present
- keyspace/table autocreate is disabled
- deletes are disabled
- reconnect-on-init is enabled
- target partition size is positive
- Cassandra is reachable
- the configured local datacenter matches `system.local`
- the configured keyspace and required journal tables exist

Failures are explicit ADTs: unavailable Cassandra, missing keyspace, missing journal table,
or invalid Cassandra persistence configuration.

## Local Bootstrap

Start Cassandra and run the journal migration:

```powershell
docker compose up -d cassandra cassandra-migrate
```

Verify health and migration output:

```powershell
docker compose ps
docker compose logs cassandra
docker compose logs cassandra-migrate
```

Run Cassandra integration tests:

```powershell
sbt integrationTests/Test/testFull
```

Stop local Cassandra without deleting journal data:

```powershell
docker compose stop cassandra
```

Destructive cleanup, deletes the named local Cassandra volume:

```powershell
docker compose down -v
```

## Integration Coverage

The Phase 4 integration tests use `cassandra:5.0.8` through Testcontainers and cover:

- migration idempotency and required schema columns
- startup validation success against a migrated Cassandra
- unavailable Cassandra fail-closed behavior
- missing keyspace and missing table fail-closed behavior
- ActorSystem #1 persistence followed by full termination and ActorSystem #2 recovery
- pending capture duplicate/mismatch semantics after recovery
- unknown capture recovery preserving provider operation identity
- payment aggregate isolation in the shared journal
- no false `Accepted` reply when the journal schema is missing
- Cassandra journal rows containing the expected serializer id and v1 serializer manifest
- static v1 serializer fixtures loaded from resources rather than generated at test runtime

Persistence TestKit and Cassandra Testcontainers cover different risks. Persistence TestKit
checks actor persistence semantics quickly without a real database. Cassandra Testcontainers
checks actual backend integration, durable storage across ActorSystem restarts, schema
validation, and journal-row serializer evidence.

## Failure Semantics

| Failure | Expected behavior | Operator-visible result | Retry behavior |
| --- | --- | --- | --- |
| Cassandra unavailable at startup | Startup validation returns `CassandraUnavailable`. | Explicit validation error message. | Driver reconnect-on-init is enabled; no custom application retry loop is implemented. |
| Missing keyspace | Startup validation returns `MissingKeyspace`. | Explicit missing keyspace message. | Runtime does not autocreate schema. |
| Missing journal table | Startup validation returns `MissingJournalTable`. | Explicit missing table message. | Runtime does not autocreate schema. |
| Journal write failure | `PaymentEntity.Accepted` is not emitted for the failed write. | Actor termination/supervision signal from Pekko Persistence. | No custom command retry is implemented. |
| Corrupt event history | Recovery fails loudly before command handling fabricates state. | Actor termination/recovery failure. | No automatic repair is implemented. |
| Serializer incompatibility | Deserialization fails with `NotSerializableException`. | Recovery/test failure identifying serializer incompatibility. | No upcaster exists in Phase 4. |

## Single Writer Interaction

One active `PaymentEntity` per `PersistenceId` remains a hard invariant. Phase 4 proves
durable recovery for a single writer over a Cassandra journal. Distributed enforcement via
Cluster Sharding is still deferred.

## Explicit Non-Goals

- production Cassandra cluster topology
- HA or multi-DC Cassandra
- Cassandra authentication, mTLS, encryption, KMS, backups, or PITR
- CQRS read models or application Cassandra query tables
- Pekko Projections
- Cluster Sharding
- snapshots
- provider side effects and reconciliation workers
- production event upcasters
- production-ready persistence operations

## Production Gaps

Phase 4 establishes local and CI durability evidence only. Before production, the project
still needs operational Cassandra topology, authentication/TLS, backup and restore,
retention policy, capacity measurements, alerting, schema deployment ownership, and event
schema evolution/upcasters.
