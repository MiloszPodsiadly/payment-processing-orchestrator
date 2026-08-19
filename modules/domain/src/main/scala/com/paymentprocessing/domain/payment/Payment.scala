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

final class Payment private (
    val paymentId: PaymentId,
    val tenantId: TenantId,
    val customerId: CustomerId,
    val merchantId: MerchantId,
    val amount: Money,
    val paymentMethodToken: PaymentMethodToken,
    val createdAt: Instant
):
  override def equals(other: Any): Boolean =
    other match
      case that: Payment =>
        paymentId == that.paymentId &&
        tenantId == that.tenantId &&
        customerId == that.customerId &&
        merchantId == that.merchantId &&
        amount == that.amount &&
        paymentMethodToken == that.paymentMethodToken &&
        createdAt == that.createdAt
      case _ => false

  override def hashCode(): Int =
    Seq(paymentId, tenantId, customerId, merchantId, amount, paymentMethodToken, createdAt).hashCode

  override def toString: String =
    s"Payment($paymentId,$tenantId,$customerId,$merchantId,$amount,$paymentMethodToken,$createdAt)"

object Payment:
  def apply(
      paymentId: PaymentId,
      tenantId: TenantId,
      customerId: CustomerId,
      merchantId: MerchantId,
      amount: Money,
      paymentMethodToken: PaymentMethodToken,
      createdAt: Instant
  ): Payment =
    new Payment(paymentId, tenantId, customerId, merchantId, amount, paymentMethodToken, createdAt)

final case class AuthorizationRecord(
    operationId: ProviderOperationId,
    occurredAt: Instant
)

final case class CaptureRecord(
    operationId: ProviderOperationId,
    amount: Money,
    occurredAt: Instant
)

final case class RefundRecord(
    refundId: RefundId,
    operationId: ProviderOperationId,
    amount: Money,
    occurredAt: Instant
)

final case class PendingRefund(
    refundId: RefundId,
    operationId: ProviderOperationId,
    amount: Money,
    requestedAt: Instant
)
