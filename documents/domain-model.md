# Domain Model

PHASE 2 implements the pure payment domain only. It contains no Pekko, Tapir,
Cassandra, HTTP, JSON, JWT, logging, metrics, clock access, random ID generation, or
external side effects.

## Typed Identifiers

The domain uses distinct domain identifier types:

- `PaymentId`
- `TenantId`
- `CustomerId`
- `MerchantId`
- `RefundId`
- `ProviderOperationId`
- `PaymentMethodToken`

UUID-backed identifiers are constructed from UUID values supplied by outer layers. The
domain does not generate UUIDs. `ProviderOperationId` rejects blank strings and
canonicalizes surrounding whitespace by storing the trimmed value. `PaymentMethodToken`
is a small immutable value object with an explicit raw `value` accessor for future
adapters and a redacted textual representation for diagnostics.

## Money And Currency

`Currency` is a closed ADT for `PLN`, `EUR`, `USD`, and `GBP`. Each currency exposes its
own `minorUnitScale`; the current currencies use scale `2`, but the model does not encode
that as a universal rule.

`Money` is constructed through a smart constructor:

- amount must be positive
- fractional precision must not exceed `currency.minorUnitScale`
- trailing zero precision may be normalized
- invalid precision is rejected
- no `Double` or `Float` construction API exists
- no rounding is used to make invalid external input valid

## Payment Core

`Payment` is immutable core data created by `PaymentCreated`:

- `PaymentId`
- `TenantId`
- `CustomerId`
- `MerchantId`
- `Money`
- `PaymentMethodToken`
- `createdAt`

After creation, legal transitions preserve this core data. Time is supplied by commands;
the domain does not call `Instant.now()`.

## State, Commands, Events And Errors

`PaymentState`, `PaymentCommand`, `PaymentEvent`, and `PaymentError` are closed ADTs.
There is no string status field.

`PaymentState` models pending and unknown provider operations with the relevant
`ProviderOperationId`. Refund states retain `RefundId`, refund amount, completed refund
history, and capture amount so refund bounds can be enforced.

`PaymentCommand` represents intent or externally supplied results. Commands that refer to
provider mutations carry a supplied `ProviderOperationId`. Within one payment aggregate,
one `ProviderOperationId` represents one logical provider mutation across authorization,
capture, and refund. Refund commands also carry a supplied `RefundId`.

`PaymentEvent` represents facts. Events for provider operation lifecycle retain operation
identity so historical replay remains unambiguous. Phase 2 intentionally does not retain
future reconciliation placeholder events; only facts with current `decide` / `evolve`
semantics are part of the domain contract.

`PaymentError` represents expected business rejections. Invalid persisted event history is
not a business rejection; `evolve` fails loudly with `InvalidPaymentHistory`.

## Decision Model

`PaymentDecider.decide(state, command)` is pure and returns:

- `Left(PaymentError)` for expected business rejection
- `Right(events)` for accepted facts
- `Right(Nil)` for duplicate-safe no-op replay where explicitly supported

`PaymentDecider.evolve(state, event)` is pure and applies persisted facts to state. Replay
is validated as an input boundary: provider result identity must match the pending or
unknown operation identity, and refund replay validates `RefundId`, `ProviderOperationId`,
amount, currency, total refund bounds, and partial/full event semantics.

## Duplicate Semantics

Duplicate-safe no-op is explicit:

- repeating the same in-flight authorization command with the same operation ID returns `Right(Nil)`
- repeating the same in-flight capture command with the same operation ID returns `Right(Nil)`
- repeating the same in-flight refund command with the same `RefundId`, operation ID, and amount returns `Right(Nil)`
- repeating the exact already completed `RefundPayment` returns `Right(Nil)`
- repeating an already applied success result for the same operation returns `Right(Nil)`
- repeating an already applied refund failure result for the same operation returns `Right(Nil)`

Conflicting duplicates are rejected:

- same refund ID with different data returns `DuplicateRefundConflict`
- a different logical provider mutation reusing an authorization, capture, or refund operation ID returns `ProviderOperationAlreadyUsed`
- same provider operation ID with conflicting outcome returns `ConflictingOperationOutcome`
- stale provider results for a different operation return `OperationMismatch`

## Unknown Outcomes

Unknown provider outcomes are first-class states:

- `AuthorizationUnknown`
- `CaptureUnknown`
- `RefundUnknown`

Unknown is not failure. Fresh mutation commands cannot bypass an unknown state. Leaving an
unknown state requires an explicit resolution command for the same provider operation ID.

## Implemented Invariants

- I-01: no second logical capture
- I-02: `totalRefunded <= capturedAmount`
- I-03: declined payment cannot capture
- I-04: fraud-rejected payment cannot authorize
- I-05: duplicate commands cannot duplicate provider mutation intent
- I-06: unknown is not treated as definitive failure
- I-07: unknown outcome cannot be bypassed with unsafe retry
- I-16: legal state transitions only
- I-17: refund requires capture
- I-18: payment amount and currency are immutable after creation
- I-19: persisted provider results must correlate to the current pending/unknown operation
- I-20: corrupt refund replay cannot alter refund ID, operation ID, amount, currency, total bound, or partial/full meaning
- I-21: payment diagnostics redact `PaymentMethodToken`
- I-22: accepted logical provider mutations inside one aggregate do not reuse `ProviderOperationId`

`PaymentState` remains public/readable for Phase 3 runtime integration. Supporting
operation/refund records use package-restricted constructors so external modules cannot
casually manufacture the invariant-sensitive pieces of successful financial states.
Authoritative states must originate from `decide` + `evolve`; runtime code must not
manually construct successful financial states from external data. Scala enum case
construction is not fully hidden in Phase 2; this is an explicit trust boundary rather
than a claimed compiler guarantee.

Deferred to later phases:

- API idempotency-key storage
- provider deterministic operation ID generation
- Pekko persistence and recovery
- Cassandra read models
- authorization and tenant enforcement
- audit records
- projection idempotency
