package com.paymentprocessing.domain.identity

import munit.FunSuite

import java.util.UUID

final class IdentifiersSuite extends FunSuite:
  test("constructs strongly typed UUID-backed identifiers from supplied UUIDs") {
    val paymentUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val tenantUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val customerUuid = UUID.fromString("00000000-0000-0000-0000-000000000003")
    val merchantUuid = UUID.fromString("00000000-0000-0000-0000-000000000004")
    val refundUuid = UUID.fromString("00000000-0000-0000-0000-000000000005")

    assertEquals(PaymentId.from(paymentUuid).value, paymentUuid)
    assertEquals(TenantId.from(tenantUuid).value, tenantUuid)
    assertEquals(CustomerId.from(customerUuid).value, customerUuid)
    assertEquals(MerchantId.from(merchantUuid).value, merchantUuid)
    assertEquals(RefundId.from(refundUuid).value, refundUuid)
  }

  test("identifier public APIs keep ID types distinct") {
    def paymentIdentity(id: PaymentId): UUID =
      id.value

    val uuid = UUID.fromString("00000000-0000-0000-0000-000000000010")

    assertEquals(paymentIdentity(PaymentId.from(uuid)), uuid)
  }

  test("rejects blank provider operation identifiers") {
    assertEquals(
      ProviderOperationId.from("   "),
      Left(InvalidProviderOperationId.Blank)
    )
  }

  test("accepts non-blank provider operation identifiers") {
    assertEquals(
      ProviderOperationId.from("provider-operation-1").map(_.value),
      Right("provider-operation-1")
    )
  }

  test("rejects blank payment method tokens") {
    assertEquals(
      PaymentMethodToken.from("\t"),
      Left(InvalidPaymentMethodToken.Blank)
    )
  }

  test("accepts non-blank payment method tokens") {
    assertEquals(
      PaymentMethodToken.from("tok_customer_1").map(_.value),
      Right("tok_customer_1")
    )
  }
