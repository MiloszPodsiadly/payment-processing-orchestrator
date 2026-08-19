package com.paymentprocessing.domain.payment

enum PaymentState:
  case NotCreated
  case Created(payment: Payment)
  case FraudCheckPending(payment: Payment)
  case FraudRejected(payment: Payment)
  case ManualReview(payment: Payment)
  case ReadyForAuthorization(payment: Payment)
  case AuthorizationPending(
      payment: Payment,
      operationId: com.paymentprocessing.domain.identity.ProviderOperationId
  )
  case Authorized(payment: Payment, authorization: AuthorizationRecord)
  case Declined(payment: Payment, authorization: AuthorizationRecord)
  case AuthorizationUnknown(
      payment: Payment,
      operationId: com.paymentprocessing.domain.identity.ProviderOperationId
  )
  case CapturePending(
      payment: Payment,
      authorization: AuthorizationRecord,
      operationId: com.paymentprocessing.domain.identity.ProviderOperationId
  )
  case CaptureFailed(
      payment: Payment,
      authorization: AuthorizationRecord,
      operationId: com.paymentprocessing.domain.identity.ProviderOperationId
  )
  case CaptureUnknown(
      payment: Payment,
      authorization: AuthorizationRecord,
      operationId: com.paymentprocessing.domain.identity.ProviderOperationId
  )
  case Captured(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      refunds: List[RefundRecord]
  )
  case RefundPending(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      completedRefunds: List[RefundRecord],
      pendingRefund: PendingRefund
  )
  case PartiallyRefunded(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      refunds: List[RefundRecord]
  )
  case RefundFailed(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      completedRefunds: List[RefundRecord],
      failedRefund: PendingRefund
  )
  case RefundUnknown(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      completedRefunds: List[RefundRecord],
      unknownRefund: PendingRefund
  )
  case Refunded(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      refunds: List[RefundRecord]
  )
