package com.paymentprocessing.domain.payment

import com.paymentprocessing.domain.identity.CustomerId
import com.paymentprocessing.domain.identity.MerchantId
import com.paymentprocessing.domain.identity.PaymentId
import com.paymentprocessing.domain.identity.PaymentMethodToken
import com.paymentprocessing.domain.identity.ProviderOperationId
import com.paymentprocessing.domain.identity.RefundId
import com.paymentprocessing.domain.identity.TenantId
import com.paymentprocessing.domain.money.Currency
import com.paymentprocessing.domain.money.Money
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class PaymentProtocolSuite extends FunSuite:
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  test("payment core data is immutable and keeps supplied creation values") {
    val payment = samplePayment

    assertEquals(payment.paymentId, samplePaymentId)
    assertEquals(payment.tenantId, sampleTenantId)
    assertEquals(payment.customerId, sampleCustomerId)
    assertEquals(payment.merchantId, sampleMerchantId)
    assertEquals(payment.amount, sampleMoney)
    assertEquals(payment.paymentMethodToken, samplePaymentMethodToken)
    assertEquals(payment.createdAt, now)
  }

  test("authorization pending state carries current provider operation identity") {
    val operationId = providerOperation("auth-1")
    val state = PaymentState.AuthorizationPending(samplePayment, operationId)

    state match
      case PaymentState.AuthorizationPending(_, actualOperationId) =>
        assertEquals(actualOperationId, operationId)
      case other => fail(s"Unexpected state: $other")
  }

  test("unknown capture state retains ambiguous provider operation identity") {
    val authorization = AuthorizationRecord(providerOperation("auth-1"), now)
    val captureOperationId = providerOperation("capture-1")
    val state = PaymentState.CaptureUnknown(samplePayment, authorization, captureOperationId)

    state match
      case PaymentState.CaptureUnknown(_, _, actualOperationId) =>
        assertEquals(actualOperationId, captureOperationId)
      case other => fail(s"Unexpected state: $other")
  }

  test("refund pending state carries refund identity, provider operation identity and amount") {
    val pendingRefund =
      PendingRefund(sampleRefundId, providerOperation("refund-1"), sampleMoney, now)
    val state =
      PaymentState.RefundPending(
        samplePayment,
        AuthorizationRecord(providerOperation("auth-1"), now),
        CaptureRecord(providerOperation("capture-1"), sampleMoney, now),
        completedRefunds = Nil,
        pendingRefund = pendingRefund
      )

    state match
      case PaymentState.RefundPending(_, _, _, _, actualPendingRefund) =>
        assertEquals(actualPendingRefund.refundId, sampleRefundId)
        assertEquals(actualPendingRefund.operationId, providerOperation("refund-1"))
        assertEquals(actualPendingRefund.amount, sampleMoney)
      case other => fail(s"Unexpected state: $other")
  }

  test("captured state contains financial history required for refund bounds") {
    val refund =
      RefundRecord(sampleRefundId, providerOperation("refund-1"), sampleMoney, now)
    val state =
      PaymentState.Captured(
        samplePayment,
        AuthorizationRecord(providerOperation("auth-1"), now),
        CaptureRecord(providerOperation("capture-1"), sampleMoney, now),
        refunds = List(refund)
      )

    state match
      case PaymentState.Captured(_, _, capture, refunds) =>
        assertEquals(capture.amount, sampleMoney)
        assertEquals(refunds, List(refund))
      case other => fail(s"Unexpected state: $other")
  }

  test("commands and events are typed ADTs rather than string statuses") {
    val command: PaymentCommand =
      PaymentCommand.AuthorizePayment(providerOperation("auth-1"), now)
    val event: PaymentEvent =
      PaymentEvent.AuthorizationRequested(providerOperation("auth-1"), now)
    val state: PaymentState =
      PaymentState.ReadyForAuthorization(samplePayment)

    assertEquals(command.productPrefix, "AuthorizePayment")
    assertEquals(event.productPrefix, "AuthorizationRequested")
    assertEquals(state.productPrefix, "ReadyForAuthorization")
  }

  private def samplePayment =
    Payment(
      samplePaymentId,
      sampleTenantId,
      sampleCustomerId,
      sampleMerchantId,
      sampleMoney,
      samplePaymentMethodToken,
      now
    )

  private def samplePaymentId =
    PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000101"))

  private def sampleTenantId =
    TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000102"))

  private def sampleCustomerId =
    CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000103"))

  private def sampleMerchantId =
    MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000104"))

  private def sampleRefundId =
    RefundId.from(UUID.fromString("00000000-0000-0000-0000-000000000105"))

  private def sampleMoney =
    Money.from(BigDecimal("100.00"), Currency.PLN).fold(error => fail(error.toString), identity)

  private def samplePaymentMethodToken =
    PaymentMethodToken.from("tok_test_1").fold(error => fail(error.toString), identity)

  private def providerOperation(value: String) =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)
