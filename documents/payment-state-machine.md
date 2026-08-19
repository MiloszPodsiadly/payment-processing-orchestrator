# Payment State Machine

PHASE 2 implements the pure state machine and decision model. It does not call providers,
persist events, schedule reconciliation, expose HTTP endpoints, or implement actors.

## Diagram

```text
NotCreated
  -> Created
  -> FraudCheckPending
       -> FraudRejected
       -> ManualReview
            -> ReadyForAuthorization
            -> FraudRejected
       -> ReadyForAuthorization
            -> AuthorizationPending
                 -> Authorized
                 -> Declined
                 -> AuthorizationUnknown
                      -> Authorized
                      -> Declined
            -> CapturePending
                 -> Captured
                 -> CaptureFailed
                 -> CaptureUnknown
                      -> Captured
                      -> CaptureFailed
            -> RefundPending
                 -> PartiallyRefunded
                 -> Refunded
                 -> RefundFailed
                 -> RefundUnknown
                      -> PartiallyRefunded
                      -> Refunded
                      -> RefundFailed
```

## States

| State | Legal Commands | Important Illegal Commands | Operation Identity | Ambiguous | Reconciliation |
| --- | --- | --- | --- | --- | --- |
| `NotCreated` | `CreatePayment` | authorize, capture, refund | none | no | no |
| `Created` | `StartFraudCheck` | capture, refund | none | no | no |
| `FraudCheckPending` | fraud approved/rejected/manual-review result | authorize | none | no | no |
| `FraudRejected` | none in Phase 2 | authorize, capture | none | no | no |
| `ManualReview` | approve or reject manual review | capture | none | no | no |
| `ReadyForAuthorization` | `AuthorizePayment` | capture | authorization operation supplied by command | no | no |
| `AuthorizationPending` | authorization success/decline/unknown result for same operation | second different authorization | current authorization operation | no | no |
| `Authorized` | `CapturePayment` | refund before capture | authorization record | no | no |
| `Declined` | none in Phase 2 | capture | authorization record | no | no |
| `AuthorizationUnknown` | explicit authorization resolution for same operation | fresh authorization | ambiguous authorization operation | yes | required |
| `CapturePending` | capture success/failure/unknown result for same operation | second different capture | current capture operation | no | no |
| `CaptureFailed` | duplicate same failure result only | conflicting capture success for same operation | failed capture operation | no | no |
| `CaptureUnknown` | explicit capture resolution for same operation | fresh capture | ambiguous capture operation | yes | required |
| `Captured` | refund request within remaining amount | second capture, over-refund, wrong currency | capture record | no | no |
| `RefundPending` | refund success/failure/unknown result for same operation | conflicting refund, different operation result | current refund operation and refund ID | no | no |
| `PartiallyRefunded` | refund request within remaining amount | over-refund, wrong currency | capture record and refund history | no | no |
| `RefundFailed` | no new retry semantics in Phase 2 | conflicting result for same operation | failed refund operation | no | no |
| `RefundUnknown` | explicit refund resolution for same operation | fresh refund | ambiguous refund operation and refund ID | yes | required |
| `Refunded` | duplicate same success result only | further refund | completed refund history | no | no |

## Duplicate Policy

Safe duplicate replay returns `Right(Nil)` only when the current state already represents
the same logical command or successful result:

- same authorization operation in `AuthorizationPending`
- same capture operation in `CapturePending`
- same refund ID, operation ID, and amount in `RefundPending` or `RefundUnknown`
- same already applied authorization, capture, or refund success result

Conflicting replay returns a typed error and emits no events.

## Invalid History

Command rejection and event-history corruption are different:

- `decide` returns `PaymentError` for expected business rejections
- `evolve` throws `InvalidPaymentHistory` when a persisted event cannot legally apply to
  the current replay state

This prevents event replay from silently repairing or ignoring corrupted histories.
Replay validation includes provider operation correlation for authorization, capture and
refund outcomes. Refund replay also validates refund identity, provider operation identity,
amount, currency, total refund bound, and whether the persisted event kind is partial or
full according to the resulting financial state.

`InvalidPaymentHistory` diagnostics include state kind, event kind, and a safe reason. They
do not interpolate the full state/event graph and do not expose raw `PaymentMethodToken`
values.

## State Construction Boundary

`PaymentState` is intentionally readable so Phase 3 runtime code can hold, pattern match,
pass, and recover states. Supporting operation/refund records have package-restricted
constructors to make impossible financial states harder to manufacture outside the
domain's transition implementation. The Phase 2 contract is that authoritative state is
produced by `PaymentDecider.evolve`; runtime code must not manually manufacture successful
financial states such as `Captured`, `PartiallyRefunded`, or `Refunded` from external
inputs.
