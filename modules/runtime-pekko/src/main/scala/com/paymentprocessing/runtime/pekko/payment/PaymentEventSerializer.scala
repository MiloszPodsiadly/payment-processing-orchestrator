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
import java.io.NotSerializableException
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

    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    val version = in.readInt()
    if version != Version then throw new NotSerializableException(s"Unsupported version: $version")

    readEvent(in)

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
        out.writeUTF("PaymentCreated")
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
    in.readUTF() match
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
    out.writeUTF(tag)
    writeInstant(out, occurredAt)

  private def writeOperationEvent(
      out: DataOutputStream,
      tag: String,
      operationId: ProviderOperationId,
      occurredAt: Instant
  ): Unit =
    out.writeUTF(tag)
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
    out.writeUTF(tag)
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
    out.writeUTF(value.value)

  private def readProviderOperationId(in: DataInputStream): ProviderOperationId =
    ProviderOperationId
      .from(in.readUTF())
      .fold(error => throw new NotSerializableException(error.toString), identity)

  private def writePaymentMethodToken(out: DataOutputStream, value: PaymentMethodToken): Unit =
    out.writeUTF(value.value)

  private def readPaymentMethodToken(in: DataInputStream): PaymentMethodToken =
    PaymentMethodToken
      .from(in.readUTF())
      .fold(error => throw new NotSerializableException(error.toString), identity)

  private def writeMoney(out: DataOutputStream, value: Money): Unit =
    out.writeUTF(value.amount.bigDecimal.toPlainString)
    out.writeUTF(value.currency.productPrefix)

  private def readMoney(in: DataInputStream): Money =
    val amount = BigDecimal(in.readUTF())
    val currency = Currency.valueOf(in.readUTF())
    Money
      .from(amount, currency)
      .fold(error => throw new NotSerializableException(error.toString), identity)

  private def writeInstant(out: DataOutputStream, value: Instant): Unit =
    out.writeLong(value.getEpochSecond)
    out.writeInt(value.getNano)

  private def readInstant(in: DataInputStream): Instant =
    Instant.ofEpochSecond(in.readLong(), in.readInt().toLong)

object PaymentEventSerializer:
  val Identifier: Int = 55032031
  private val Manifest = "payment-event-v1"
  private val Version = 1
