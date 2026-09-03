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
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.deriving.Mirror
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

  test("static fixtures cover every compiler-derived PaymentEvent case") {
    assertEquals(expectedEvents.keySet, labelsOf[PaymentEvent])
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
      "FraudCheckRequested" -> PaymentEvent.FraudCheckRequested(occurredAt),
      "FraudCheckPassed" -> PaymentEvent.FraudCheckPassed(occurredAt),
      "FraudCheckRejected" -> PaymentEvent.FraudCheckRejected(occurredAt),
      "FraudManualReviewRequired" -> PaymentEvent.FraudManualReviewRequired(occurredAt),
      "FraudManualReviewApproved" -> PaymentEvent.FraudManualReviewApproved(occurredAt),
      "FraudManualReviewRejected" -> PaymentEvent.FraudManualReviewRejected(occurredAt),
      "PaymentAuthorized" -> PaymentEvent.PaymentAuthorized(
        operation("auth-fixture-1"),
        occurredAt
      ),
      "PaymentDeclined" -> PaymentEvent.PaymentDeclined(
        operation("auth-fixture-1"),
        occurredAt
      ),
      "AuthorizationOutcomeUnknown" -> PaymentEvent.AuthorizationOutcomeUnknown(
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
      "CaptureFailed" -> PaymentEvent.CaptureFailed(
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
      ),
      "PaymentRefunded" -> PaymentEvent.PaymentRefunded(
        refundId,
        operation("refund-fixture-1"),
        money("30.00"),
        occurredAt
      ),
      "RefundFailed" -> PaymentEvent.RefundFailed(
        operation("refund-fixture-1"),
        occurredAt
      ),
      "RefundOutcomeUnknown" -> PaymentEvent.RefundOutcomeUnknown(
        operation("refund-fixture-1"),
        occurredAt
      )
    )

  private inline def labelsOf[T](using mirror: Mirror.SumOf[T]): Set[String] =
    labelsFromTuple[mirror.MirroredElemLabels]

  private inline def labelsFromTuple[Labels <: Tuple]: Set[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Set.empty
      case _: (head *: tail) =>
        Set(constValue[head].asInstanceOf[String]) ++ labelsFromTuple[tail]

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
