# Runtime Pekko Payment Entity

Phase 3 introduces `PaymentEntity` as the Apache Pekko Typed persistence shell around the
pure Phase 2 payment domain.

## Responsibility

`PaymentEntity` owns command serialization and recovery for one logical
`PaymentId`. It does not implement payment business rules. Runtime commands are translated
to `PaymentDecider.decide`, persisted domain `PaymentEvent` values are replayed through
`PaymentDecider.evolve`, and the recovered state is the resulting `PaymentState`.

Phase 3 still uses Pekko Persistence TestKit, not a production durable journal.

## Runtime Protocol

The runtime command protocol uses a minimal typed envelope:

```scala
Execute(command: PaymentCommand, replyTo: ActorRef[Reply])
```

The domain command ADT is reused directly. The entity does not duplicate the domain
command hierarchy.

Replies distinguish:

- `Accepted` for commands that persisted one or more events
- `DuplicateAccepted` for explicit domain `Right(Nil)` no-ops
- `Rejected` for domain `PaymentError` rejections
- `InvalidEnvelope` for runtime routing violations such as `PaymentEntity(A)` receiving
  `CreatePayment(B, ...)`

## Persistence Identity

Persistence IDs are deterministic and aggregate-specific:

```text
payment|<payment-uuid>
```

They do not use actor paths, timestamps, randomness, counters, or payment method tokens.

## Recovery

Recovery relies on Pekko Persistence event replay. The event handler is
owned by `PaymentEntity` only for runtime aggregate identity validation. It rejects
`PaymentCreated(paymentId = B)` during recovery of `PaymentEntity(A)` before delegating
normal event replay to `PaymentDecider.evolve`. Corrupt domain histories continue to fail
loudly through the existing domain `InvalidPaymentHistory` behavior.

Recovery tests cover Created, AuthorizationPending, Authorized, AuthorizationUnknown,
CapturePending, Captured, CaptureUnknown, RefundPending, PartiallyRefunded, RefundUnknown,
and Refunded states where those histories are legal.

## Serialization

Phase 3 binds `PaymentEvent` to an explicit runtime serializer in `runtime-pekko`.
Persistence TestKit runs with event serialization enabled. The test suite round-trips
every current concrete `PaymentEvent` case through Pekko serialization and then through
persistence recovery.

This proves current-version serialization correctness only. It does not claim
backward-compatible persisted event schema evolution, rolling upgrades, or upcaster support.

## Single Writer

One active `PaymentEntity` per `PersistenceId` is a hard runtime correctness invariant.
`PaymentEntity.apply(paymentId)` does not provide cluster-wide uniqueness by itself.
Bootstrap and application composition must not intentionally spawn multiple active
entities for the same payment. Cluster Sharding remains the deferred phase that will own
distributed single-writer enforcement.

## Phase Boundary

Phase 3 uses Pekko Persistence TestKit for actor persistence tests. This is not Cassandra
persistence. Cassandra journal wiring, schemas, and infrastructure tests remain Phase 4.

No snapshots are introduced because no measured recovery cost exists yet. No timers are
introduced because provider reconciliation and retry scheduling belong to later phases.
