package com.paymentprocessing.domain.payment

import com.paymentprocessing.domain.identity.CustomerId
import com.paymentprocessing.domain.identity.MerchantId
import com.paymentprocessing.domain.identity.PaymentId
import com.paymentprocessing.domain.identity.PaymentMethodToken
import com.paymentprocessing.domain.identity.ProviderOperationId
import com.paymentprocessing.domain.identity.RefundId
import com.paymentprocessing.domain.identity.TenantId
import com.paymentprocessing.domain.money.Money

import java.time.Instant

enum PaymentEvent:
  case PaymentCreated(
      paymentId: PaymentId,
      tenantId: TenantId,
      customerId: CustomerId,
      merchantId: MerchantId,
      amount: Money,
      paymentMethodToken: PaymentMethodToken,
      occurredAt: Instant
  )

  case FraudCheckRequested(occurredAt: Instant)
  case FraudCheckPassed(occurredAt: Instant)
  case FraudCheckRejected(occurredAt: Instant)
  case FraudManualReviewRequired(occurredAt: Instant)
  case FraudManualReviewApproved(occurredAt: Instant)
  case FraudManualReviewRejected(occurredAt: Instant)

  case AuthorizationRequested(operationId: ProviderOperationId, occurredAt: Instant)
  case PaymentAuthorized(operationId: ProviderOperationId, occurredAt: Instant)
  case PaymentDeclined(operationId: ProviderOperationId, occurredAt: Instant)
  case AuthorizationOutcomeUnknown(operationId: ProviderOperationId, occurredAt: Instant)

  case CaptureRequested(operationId: ProviderOperationId, occurredAt: Instant)
  case PaymentCaptured(operationId: ProviderOperationId, occurredAt: Instant)
  case CaptureFailed(operationId: ProviderOperationId, occurredAt: Instant)
  case CaptureOutcomeUnknown(operationId: ProviderOperationId, occurredAt: Instant)

  case RefundRequested(
      refundId: RefundId,
      operationId: ProviderOperationId,
      amount: Money,
      occurredAt: Instant
  )
  case PaymentPartiallyRefunded(
      refundId: RefundId,
      operationId: ProviderOperationId,
      amount: Money,
      occurredAt: Instant
  )
  case PaymentRefunded(
      refundId: RefundId,
      operationId: ProviderOperationId,
      amount: Money,
      occurredAt: Instant
  )
  case RefundFailed(operationId: ProviderOperationId, occurredAt: Instant)
  case RefundOutcomeUnknown(operationId: ProviderOperationId, occurredAt: Instant)
