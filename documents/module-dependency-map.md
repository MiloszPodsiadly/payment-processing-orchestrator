# Module Dependency Map

Phase 1 structurally defines the project graph. It does not implement payment business behavior.

## Direction

```text
domain
  <- application
    <- runtime / adapters / security
      <- bootstrap
        <- integration-tests
```

## Modules

| Module | Responsibility | Allowed Dependencies | Forbidden Dependencies |
| --- | --- | --- | --- |
| `domain` | Pure payment language, invariants, commands, events, errors, and state decisions in Phase 2. | Scala standard library, test libraries in test scope. | Pekko, Tapir, Cassandra driver, HTTP, JWT, logging implementation, metrics implementation, Typesafe Config. |
| `application` | Use-case coordination and ports around the domain. | `domain`, test libraries in test scope. | Tapir, HTTP implementation, Cassandra row models, provider transport DTOs, actor protocol types. |
| `runtime-pekko` | Typed actors, persistence behavior, recovery, and runtime-owned current-version event serialization. Later sharding remains out of scope until its phase. | `application`, Pekko Typed, Pekko Persistence Typed, test-scope Pekko actor/persistence testkit. | HTTP DTO ownership, Cassandra row ownership, Tapir, Cluster Sharding, Projections, domain redefinition. |
| `adapter-http-tapir` | HTTP contracts and DTO mapping when API work begins. | `application`, `domain` transitively through application, `security` only when endpoint policies exist. | Payment state authority, direct persistence writes. |
| `adapter-cassandra` | Phase 4 Cassandra journal schema, Pekko Persistence Cassandra dependency, version-alignment dependencies, and startup validation. Read models remain deferred. | `application`, Pekko Connectors Cassandra, Pekko Persistence Cassandra, reviewed Pekko cluster/coordination alignment dependencies. | Domain decisions, HTTP contracts, payment invariant ownership, Tapir, Cluster Sharding, Projections. |
| `adapter-provider` | Mock and later external provider adapter implementations. | `application`. | Domain concept redefinition, unsafe retry ownership. |
| `adapter-fraud` | Fraud gateway adapter implementations. | `application`. | Payment lifecycle authority. |
| `security` | Authentication boundary, permissions, RBAC, and tenant isolation. | `application`. | Client-side authority assumptions, domain event ownership. |
| `observability` | Runtime logging/metrics/tracing support. | Scala/JVM observability libraries when introduced. | Business state authority, high-cardinality metric identity labels. |
| `bootstrap` | Composition root and startup configuration boundary. | Runtime, adapters, security, observability. | Business decision logic. |
| `integration-tests` | Cross-module test wiring and infrastructure tests. | `bootstrap` and test dependencies. | Production code ownership. |

## Enforcement

- sbt project dependencies point inward only.
- `verifyArchitecture` fails if expected module directories are missing.
- `verifyArchitecture` restricts `domain` production compile dependencies to an explicit approved set: `org.scala-lang:scala3-library_3` and `org.scala-lang:scala-library`.
- `verifyArchitecture` restricts `application` production compile dependencies to the same explicit approved external set plus its approved structural project coordinate `com.paymentprocessing:payment-domain_3`.
- Unknown external compile dependencies in `domain` or `application` fail until they receive architecture approval and the approved set is deliberately extended.
- Unknown direct production dependencies in `runtime-pekko` fail until they receive architecture approval and the approved set is deliberately extended.
- A small known-forbidden family check remains as defense in depth; it is not the primary guarantee.
- `verifyArchitecture` checks the direct sbt project graph against the approved dependency direction.
- Test-scope libraries are allowed and are not treated as compile dependency violations.
- `bootstrap` composes modules and owns typed startup configuration loading.
- `integration-tests` includes a secondary source-boundary scan that fails if forbidden framework imports appear in `domain` or `application`.
- Phase 3 extends the runtime boundary checks so `runtime-pekko` may use approved Pekko Typed/Persistence dependencies but remains free of Cassandra, Tapir, Cluster Sharding, and Projections.
- Phase 4 extends the adapter boundary checks so `adapter-cassandra` may use approved Pekko Cassandra dependencies while staying free of Tapir, Cluster Sharding, and Projections.
- One active `PaymentEntity` per `PersistenceId` is a hard runtime invariant. Phase 3 documents this requirement but does not implement distributed enforcement; Cluster Sharding remains deferred.
- The source-boundary scan has fixture tests proving it fails for missing expected directories and injected forbidden imports.
