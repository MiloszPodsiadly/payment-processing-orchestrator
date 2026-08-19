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

enum PaymentCommand:
  case CreatePayment(
      paymentId: PaymentId,
      tenantId: TenantId,
      customerId: CustomerId,
      merchantId: MerchantId,
      amount: Money,
      paymentMethodToken: PaymentMethodToken,
      occurredAt: Instant
  )

  case StartFraudCheck(occurredAt: Instant)
  case RecordFraudApproved(occurredAt: Instant)
  case RecordFraudRejected(occurredAt: Instant)
  case RecordFraudManualReview(occurredAt: Instant)
  case ApproveManualReview(occurredAt: Instant)
  case RejectManualReview(occurredAt: Instant)

  case AuthorizePayment(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordAuthorizationSucceeded(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordAuthorizationDeclined(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordAuthorizationUnknown(operationId: ProviderOperationId, occurredAt: Instant)
  case ResolveAuthorizationUnknownAsSucceeded(operationId: ProviderOperationId, occurredAt: Instant)
  case ResolveAuthorizationUnknownAsDeclined(operationId: ProviderOperationId, occurredAt: Instant)

  case CapturePayment(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordCaptureSucceeded(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordCaptureFailed(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordCaptureUnknown(operationId: ProviderOperationId, occurredAt: Instant)
  case ResolveCaptureUnknownAsSucceeded(operationId: ProviderOperationId, occurredAt: Instant)
  case ResolveCaptureUnknownAsFailed(operationId: ProviderOperationId, occurredAt: Instant)

  case RefundPayment(
      refundId: RefundId,
      operationId: ProviderOperationId,
      amount: Money,
      occurredAt: Instant
  )
  case RecordRefundSucceeded(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordRefundFailed(operationId: ProviderOperationId, occurredAt: Instant)
  case RecordRefundUnknown(operationId: ProviderOperationId, occurredAt: Instant)
  case ResolveRefundUnknownAsSucceeded(operationId: ProviderOperationId, occurredAt: Instant)
  case ResolveRefundUnknownAsFailed(operationId: ProviderOperationId, occurredAt: Instant)
