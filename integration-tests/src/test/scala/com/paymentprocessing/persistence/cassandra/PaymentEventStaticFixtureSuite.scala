package com.paymentprocessing.persistence.cassandra

import com.paymentprocessing.domain.identity.CustomerId
import com.paymentprocessing.domain.identity.MerchantId
import com.paymentprocessing.domain.identity.PaymentId
import com.paymentprocessing.domain.identity.PaymentMethodToken
import com.paymentprocessing.domain.identity.ProviderOperationId
import com.paymentprocessing.domain.identity.RefundId
import com.paymentprocessing.domain.identity.TenantId
import com.paymentprocessing.domain.money.Currency
import com.paymentprocessing.domain.money.Money
import com.paymentprocessing.domain.payment.PaymentEvent
import com.paymentprocessing.runtime.pekko.payment.PaymentEventSerializer
import munit.FunSuite

import java.io.NotSerializableException
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import scala.jdk.CollectionConverters._

final class PaymentEventStaticFixtureSuite extends FunSuite:
  private val fixtureResource = "serializer-fixtures/payment-event-v1.properties"
  private val manifest = "payment-event-v1"
  private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
  private val serializer = new PaymentEventSerializer(null)

  test("static v1 fixtures deserialize to the frozen payment event contract") {
    val fixtures = loadFixtures()

    assertEquals(fixtures.getProperty("manifest"), manifest)
    assertEquals(fixtures.getProperty("identifier").toInt, PaymentEventSerializer.Identifier)

    expectedEvents.foreach { case (label, expected) =>
      assertEquals(serializer.manifest(expected), manifest)
      assertEquals(
        serializer.fromBinary(decode(fixtures, label), manifest),
        expected
      )
    }
  }

  test("static v1 fixture corruption is rejected deterministically") {
    val fixtures = loadFixtures()
    val payload = decode(fixtures, "PaymentCreated").dropRight(1)

    intercept[NotSerializableException] {
      serializer.fromBinary(payload, manifest)
    }
  }

  test("fixture file contains no generated-at-runtime event entries") {
    val fixtures = loadFixtures()
    val eventKeys =
      fixtures.stringPropertyNames().asScala.toSet -- Set("manifest", "identifier")

    assertEquals(eventKeys, expectedEvents.keySet)
  }

  private def loadFixtures(): Properties =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(fixtureResource))
      .getOrElse(fail(s"Missing fixture resource: $fixtureResource"))
    try
      val properties = Properties()
      properties.load(stream)
      properties
    finally stream.close()

  private def decode(fixtures: Properties, key: String): Array[Byte] =
    Base64.getDecoder.decode(
      Option(fixtures.getProperty(key)).getOrElse(fail(s"Missing fixture key: $key"))
    )

  private def expectedEvents: Map[String, PaymentEvent] =
    Map(
      "PaymentCreated" -> PaymentEvent.PaymentCreated(
        paymentId,
        tenantId,
        customerId,
        merchantId,
        money("100.00"),
        token("tok_fixture_1"),
        occurredAt
      ),
      "AuthorizationRequested" -> PaymentEvent.AuthorizationRequested(
        operation("auth-fixture-1"),
        occurredAt
      ),
      "PaymentAuthorized" -> PaymentEvent.PaymentAuthorized(
        operation("auth-fixture-1"),
        occurredAt
      ),
      "CaptureRequested" -> PaymentEvent.CaptureRequested(
        operation("capture-fixture-1"),
        occurredAt
      ),
      "PaymentCaptured" -> PaymentEvent.PaymentCaptured(
        operation("capture-fixture-1"),
        occurredAt
      ),
      "CaptureOutcomeUnknown" -> PaymentEvent.CaptureOutcomeUnknown(
        operation("capture-fixture-unknown"),
        occurredAt
      ),
      "RefundRequested" -> PaymentEvent.RefundRequested(
        refundId,
        operation("refund-fixture-1"),
        money("30.00"),
        occurredAt
      ),
      "PaymentPartiallyRefunded" -> PaymentEvent.PaymentPartiallyRefunded(
        refundId,
        operation("refund-fixture-1"),
        money("30.00"),
        occurredAt
      )
    )

  private def paymentId: PaymentId =
    PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000f01"))

  private def tenantId: TenantId =
    TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000f02"))

  private def customerId: CustomerId =
    CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000f03"))

  private def merchantId: MerchantId =
    MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000f04"))

  private def refundId: RefundId =
    RefundId.from(UUID.fromString("00000000-0000-0000-0000-000000000f05"))

  private def operation(value: String): ProviderOperationId =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)

  private def token(value: String): PaymentMethodToken =
    PaymentMethodToken.from(value).fold(error => fail(error.toString), identity)

  private def money(value: String): Money =
    Money.from(BigDecimal(value), Currency.PLN).fold(error => fail(error.toString), identity)
