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
| `runtime-pekko` | Typed actors, persistence behavior, recovery, and later sharding. | `application`, test-scope Pekko actor testkit. | HTTP DTO ownership, Cassandra row ownership, domain redefinition. |
| `adapter-http-tapir` | HTTP contracts and DTO mapping when API work begins. | `application`, `domain` transitively through application, `security` only when endpoint policies exist. | Payment state authority, direct persistence writes. |
| `adapter-cassandra` | Cassandra journal/read-model integration when persistence begins. | `application`. | Domain decisions, HTTP contracts, payment invariant ownership. |
| `adapter-provider` | Mock and later external provider adapter implementations. | `application`. | Domain concept redefinition, unsafe retry ownership. |
| `adapter-fraud` | Fraud gateway adapter implementations. | `application`. | Payment lifecycle authority. |
| `security` | Authentication boundary, permissions, RBAC, and tenant isolation. | `application`. | Client-side authority assumptions, domain event ownership. |
| `observability` | Runtime logging/metrics/tracing support. | Scala/JVM observability libraries when introduced. | Business state authority, high-cardinality metric identity labels. |
| `bootstrap` | Composition root and startup configuration boundary. | Runtime, adapters, security, observability. | Business decision logic. |
| `integration-tests` | Cross-module test wiring and infrastructure tests. | `bootstrap` and test dependencies. | Production code ownership. |

## Enforcement

- sbt project dependencies point inward only.
- `verifyArchitecture` fails if expected module directories are missing.
- `verifyArchitecture` checks that `domain` has no compile dependency on framework or infrastructure libraries.
- `verifyArchitecture` checks that `application` has no compile dependency on HTTP, storage, actor runtime, provider-client, or JWT implementation libraries.
- `verifyArchitecture` checks the direct sbt project graph against the approved dependency direction.
- Test-scope libraries are allowed and are not treated as compile dependency violations.
- `bootstrap` composes modules and owns typed startup configuration loading.
- `integration-tests` includes a secondary source-boundary scan that fails if forbidden framework imports appear in `domain` or `application`.
- The source-boundary scan has fixture tests proving it fails for missing expected directories and injected forbidden imports.
