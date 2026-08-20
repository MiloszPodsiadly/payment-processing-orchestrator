package com.paymentprocessing.runtime.pekko.payment

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
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.NotSerializableException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.annotation.unused

final class PaymentEventSerializer(@unused system: ExtendedActorSystem)
    extends SerializerWithStringManifest:
  import PaymentEventSerializer.*

  override def identifier: Int =
    Identifier

  override def manifest(value: AnyRef): String =
    value match
      case _: PaymentEvent => Manifest
      case other =>
        throw new NotSerializableException(s"Unsupported type: ${other.getClass.getName}")

  override def toBinary(value: AnyRef): Array[Byte] =
    value match
      case event: PaymentEvent =>
        val bytes = new ByteArrayOutputStream()
        val out = new DataOutputStream(bytes)
        out.writeInt(Version)
        writeEvent(out, event)
        out.flush()
        bytes.toByteArray
      case other =>
        throw new NotSerializableException(s"Unsupported type: ${other.getClass.getName}")

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    if manifest != Manifest then
      throw new NotSerializableException(s"Unsupported manifest: $manifest")

    try
      val in = new DataInputStream(new ByteArrayInputStream(bytes))
      val version = readInt(in, "PaymentEvent version")
      if version != Version then
        throw new NotSerializableException(s"Unsupported version: $version")

      val event = readEvent(in)
      if in.available() != 0 then
        throw new NotSerializableException("Unexpected trailing bytes in PaymentEvent payload")
      event
    catch
      case error: NotSerializableException => throw error
      case error: EOFException =>
        throw serializationFailure("Truncated PaymentEvent payload", error)
      case error: IOException =>
        throw serializationFailure("Malformed PaymentEvent payload", error)
      case error: RuntimeException =>
        throw serializationFailure("Malformed PaymentEvent payload", error)

  private def writeEvent(out: DataOutputStream, event: PaymentEvent): Unit =
    event match
      case PaymentEvent.PaymentCreated(
            paymentId,
            tenantId,
            customerId,
            merchantId,
            amount,
            paymentMethodToken,
            occurredAt
          ) =>
        writeString(out, "PaymentCreated")
        writePaymentId(out, paymentId)
        writeTenantId(out, tenantId)
        writeCustomerId(out, customerId)
        writeMerchantId(out, merchantId)
        writeMoney(out, amount)
        writePaymentMethodToken(out, paymentMethodToken)
        writeInstant(out, occurredAt)

      case PaymentEvent.FraudCheckRequested(occurredAt) =>
        writeInstantOnly(out, "FraudCheckRequested", occurredAt)
      case PaymentEvent.FraudCheckPassed(occurredAt) =>
        writeInstantOnly(out, "FraudCheckPassed", occurredAt)
      case PaymentEvent.FraudCheckRejected(occurredAt) =>
        writeInstantOnly(out, "FraudCheckRejected", occurredAt)
      case PaymentEvent.FraudManualReviewRequired(occurredAt) =>
        writeInstantOnly(out, "FraudManualReviewRequired", occurredAt)
      case PaymentEvent.FraudManualReviewApproved(occurredAt) =>
        writeInstantOnly(out, "FraudManualReviewApproved", occurredAt)
      case PaymentEvent.FraudManualReviewRejected(occurredAt) =>
        writeInstantOnly(out, "FraudManualReviewRejected", occurredAt)

      case PaymentEvent.AuthorizationRequested(operationId, occurredAt) =>
        writeOperationEvent(out, "AuthorizationRequested", operationId, occurredAt)
      case PaymentEvent.PaymentAuthorized(operationId, occurredAt) =>
        writeOperationEvent(out, "PaymentAuthorized", operationId, occurredAt)
      case PaymentEvent.PaymentDeclined(operationId, occurredAt) =>
        writeOperationEvent(out, "PaymentDeclined", operationId, occurredAt)
      case PaymentEvent.AuthorizationOutcomeUnknown(operationId, occurredAt) =>
        writeOperationEvent(out, "AuthorizationOutcomeUnknown", operationId, occurredAt)

      case PaymentEvent.CaptureRequested(operationId, occurredAt) =>
        writeOperationEvent(out, "CaptureRequested", operationId, occurredAt)
      case PaymentEvent.PaymentCaptured(operationId, occurredAt) =>
        writeOperationEvent(out, "PaymentCaptured", operationId, occurredAt)
      case PaymentEvent.CaptureFailed(operationId, occurredAt) =>
        writeOperationEvent(out, "CaptureFailed", operationId, occurredAt)
      case PaymentEvent.CaptureOutcomeUnknown(operationId, occurredAt) =>
        writeOperationEvent(out, "CaptureOutcomeUnknown", operationId, occurredAt)

      case PaymentEvent.RefundRequested(refundId, operationId, amount, occurredAt) =>
        writeRefundEvent(out, "RefundRequested", refundId, operationId, amount, occurredAt)
      case PaymentEvent.PaymentPartiallyRefunded(refundId, operationId, amount, occurredAt) =>
        writeRefundEvent(out, "PaymentPartiallyRefunded", refundId, operationId, amount, occurredAt)
      case PaymentEvent.PaymentRefunded(refundId, operationId, amount, occurredAt) =>
        writeRefundEvent(out, "PaymentRefunded", refundId, operationId, amount, occurredAt)
      case PaymentEvent.RefundFailed(operationId, occurredAt) =>
        writeOperationEvent(out, "RefundFailed", operationId, occurredAt)
      case PaymentEvent.RefundOutcomeUnknown(operationId, occurredAt) =>
        writeOperationEvent(out, "RefundOutcomeUnknown", operationId, occurredAt)

  private def readEvent(in: DataInputStream): PaymentEvent =
    readString(in, "PaymentEvent tag") match
      case "PaymentCreated" =>
        PaymentEvent.PaymentCreated(
          readPaymentId(in),
          readTenantId(in),
          readCustomerId(in),
          readMerchantId(in),
          readMoney(in),
          readPaymentMethodToken(in),
          readInstant(in)
        )
      case "FraudCheckRequested" =>
        PaymentEvent.FraudCheckRequested(readInstant(in))
      case "FraudCheckPassed" =>
        PaymentEvent.FraudCheckPassed(readInstant(in))
      case "FraudCheckRejected" =>
        PaymentEvent.FraudCheckRejected(readInstant(in))
      case "FraudManualReviewRequired" =>
        PaymentEvent.FraudManualReviewRequired(readInstant(in))
      case "FraudManualReviewApproved" =>
        PaymentEvent.FraudManualReviewApproved(readInstant(in))
      case "FraudManualReviewRejected" =>
        PaymentEvent.FraudManualReviewRejected(readInstant(in))

      case "AuthorizationRequested" =>
        PaymentEvent.AuthorizationRequested(readProviderOperationId(in), readInstant(in))
      case "PaymentAuthorized" =>
        PaymentEvent.PaymentAuthorized(readProviderOperationId(in), readInstant(in))
      case "PaymentDeclined" =>
        PaymentEvent.PaymentDeclined(readProviderOperationId(in), readInstant(in))
      case "AuthorizationOutcomeUnknown" =>
        PaymentEvent.AuthorizationOutcomeUnknown(readProviderOperationId(in), readInstant(in))

      case "CaptureRequested" =>
        PaymentEvent.CaptureRequested(readProviderOperationId(in), readInstant(in))
      case "PaymentCaptured" =>
        PaymentEvent.PaymentCaptured(readProviderOperationId(in), readInstant(in))
      case "CaptureFailed" =>
        PaymentEvent.CaptureFailed(readProviderOperationId(in), readInstant(in))
      case "CaptureOutcomeUnknown" =>
        PaymentEvent.CaptureOutcomeUnknown(readProviderOperationId(in), readInstant(in))

      case "RefundRequested" =>
        PaymentEvent.RefundRequested(
          readRefundId(in),
          readProviderOperationId(in),
          readMoney(in),
          readInstant(in)
        )
      case "PaymentPartiallyRefunded" =>
        PaymentEvent.PaymentPartiallyRefunded(
          readRefundId(in),
          readProviderOperationId(in),
          readMoney(in),
          readInstant(in)
        )
      case "PaymentRefunded" =>
        PaymentEvent.PaymentRefunded(
          readRefundId(in),
          readProviderOperationId(in),
          readMoney(in),
          readInstant(in)
        )
      case "RefundFailed" =>
        PaymentEvent.RefundFailed(readProviderOperationId(in), readInstant(in))
      case "RefundOutcomeUnknown" =>
        PaymentEvent.RefundOutcomeUnknown(readProviderOperationId(in), readInstant(in))
      case other =>
        throw new NotSerializableException(s"Unsupported PaymentEvent tag: $other")

  private def writeInstantOnly(out: DataOutputStream, tag: String, occurredAt: Instant): Unit =
    writeString(out, tag)
    writeInstant(out, occurredAt)

  private def writeOperationEvent(
      out: DataOutputStream,
      tag: String,
      operationId: ProviderOperationId,
      occurredAt: Instant
  ): Unit =
    writeString(out, tag)
    writeProviderOperationId(out, operationId)
    writeInstant(out, occurredAt)

  private def writeRefundEvent(
      out: DataOutputStream,
      tag: String,
      refundId: RefundId,
      operationId: ProviderOperationId,
      amount: Money,
      occurredAt: Instant
  ): Unit =
    writeString(out, tag)
    writeRefundId(out, refundId)
    writeProviderOperationId(out, operationId)
    writeMoney(out, amount)
    writeInstant(out, occurredAt)

  private def writeUuid(out: DataOutputStream, value: UUID): Unit =
    out.writeLong(value.getMostSignificantBits)
    out.writeLong(value.getLeastSignificantBits)

  private def readUuid(in: DataInputStream): UUID =
    UUID(in.readLong(), in.readLong())

  private def writePaymentId(out: DataOutputStream, value: PaymentId): Unit =
    writeUuid(out, value.value)

  private def readPaymentId(in: DataInputStream): PaymentId =
    PaymentId.from(readUuid(in))

  private def writeTenantId(out: DataOutputStream, value: TenantId): Unit =
    writeUuid(out, value.value)

  private def readTenantId(in: DataInputStream): TenantId =
    TenantId.from(readUuid(in))

  private def writeCustomerId(out: DataOutputStream, value: CustomerId): Unit =
    writeUuid(out, value.value)

  private def readCustomerId(in: DataInputStream): CustomerId =
    CustomerId.from(readUuid(in))

  private def writeMerchantId(out: DataOutputStream, value: MerchantId): Unit =
    writeUuid(out, value.value)

  private def readMerchantId(in: DataInputStream): MerchantId =
    MerchantId.from(readUuid(in))

  private def writeRefundId(out: DataOutputStream, value: RefundId): Unit =
    writeUuid(out, value.value)

  private def readRefundId(in: DataInputStream): RefundId =
    RefundId.from(readUuid(in))

  private def writeProviderOperationId(
      out: DataOutputStream,
      value: ProviderOperationId
  ): Unit =
    writeString(out, value.value)

  private def readProviderOperationId(in: DataInputStream): ProviderOperationId =
    ProviderOperationId
      .from(readString(in, "ProviderOperationId"))
      .fold(error => throw new NotSerializableException(error.toString), identity)

  private def writePaymentMethodToken(out: DataOutputStream, value: PaymentMethodToken): Unit =
    writeString(out, value.value)

  private def readPaymentMethodToken(in: DataInputStream): PaymentMethodToken =
    PaymentMethodToken
      .from(readString(in, "PaymentMethodToken"))
      .fold(error => throw new NotSerializableException(error.toString), identity)

  private def writeMoney(out: DataOutputStream, value: Money): Unit =
    writeString(out, value.amount.bigDecimal.toPlainString)
    writeString(out, value.currency.productPrefix)

  private def readMoney(in: DataInputStream): Money =
    val amount = BigDecimal(readString(in, "Money amount"))
    val currency = Currency.valueOf(readString(in, "Currency"))
    Money
      .from(amount, currency)
      .fold(error => throw new NotSerializableException(error.toString), identity)

  private def writeInstant(out: DataOutputStream, value: Instant): Unit =
    out.writeLong(value.getEpochSecond)
    out.writeInt(value.getNano)

  private def readInstant(in: DataInputStream): Instant =
    Instant.ofEpochSecond(in.readLong(), in.readInt().toLong)

  private def writeString(out: DataOutputStream, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    out.writeInt(bytes.length)
    out.write(bytes)

  private def readString(in: DataInputStream, field: String): String =
    val length = readInt(in, s"$field length")
    if length < 0 then
      throw new NotSerializableException(s"Negative $field length in PaymentEvent payload")

    val remaining = in.available()
    if length > remaining then
      throw new NotSerializableException(
        s"Declared $field length $length exceeds remaining PaymentEvent payload bytes $remaining"
      )

    val bytes = new Array[Byte](length)
    in.readFully(bytes)
    String(bytes, StandardCharsets.UTF_8)

  private def readInt(in: DataInputStream, field: String): Int =
    try in.readInt()
    catch
      case error: EOFException =>
        throw serializationFailure(s"Missing $field in PaymentEvent payload", error)

  private def serializationFailure(message: String, cause: Throwable): NotSerializableException =
    val failure = NotSerializableException(message)
    failure.initCause(cause)
    failure

object PaymentEventSerializer:
  val Identifier: Int = 55032031
  private val Manifest = "payment-event-v1"
  private val Version = 1
