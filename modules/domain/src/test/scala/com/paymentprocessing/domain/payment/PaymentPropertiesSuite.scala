package com.paymentprocessing.domain.payment

import com.paymentprocessing.domain.identity.CustomerId
import com.paymentprocessing.domain.identity.MerchantId
import com.paymentprocessing.domain.identity.PaymentId
import com.paymentprocessing.domain.identity.PaymentMethodToken
import com.paymentprocessing.domain.identity.ProviderOperationId
import com.paymentprocessing.domain.identity.RefundId
import com.paymentprocessing.domain.identity.TenantId
import com.paymentprocessing.domain.money.Currency
import com.paymentprocessing.domain.money.InvalidMoney
import com.paymentprocessing.domain.money.Money
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.Test

import java.time.Instant
import java.util.UUID

final class PaymentPropertiesSuite extends ScalaCheckSuite:
  override val scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(250)

  property("created Money is positive and respects currency minor-unit scale") {
    forAll(genMoney) { money =>
      money.amount > 0 &&
      effectiveScale(money.amount) <= money.currency.minorUnitScale
    }
  }

  property("Money construction does not silently round numerical value") {
    forAll(genCurrency, Gen.chooseNum(1L, 1_000_000L)) { (currency, minorUnits) =>
      val input = decimalFromMinorUnits(minorUnits, currency)

      Money.from(input, currency).exists(_.amount == input)
    }
  }

  property("Money rejects values requiring precision above the currency scale") {
    forAll(genCurrency) { currency =>
      val invalidAmount =
        BigDecimal("1." + ("0" * currency.minorUnitScale) + "1")

      Money.from(invalidAmount, currency) match
        case Left(_: InvalidMoney.UnsupportedScale) => true
        case _ => false
    }
  }

  property("payment core data is immutable across generated legal transitions") {
    forAll(genPayment) { payment =>
      val finalState = fold(
        PaymentState.NotCreated,
        List(
          createCommand(payment),
          PaymentCommand.StartFraudCheck(payment.createdAt),
          PaymentCommand.RecordFraudApproved(payment.createdAt),
          PaymentCommand.AuthorizePayment(operation("auth-core"), payment.createdAt),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-core"), payment.createdAt),
          PaymentCommand.CapturePayment(operation("capture-core"), payment.createdAt),
          PaymentCommand.RecordCaptureSucceeded(operation("capture-core"), payment.createdAt)
        )
      )

      extractPayment(finalState) == payment
    }
  }

  property("accepted refund sequences never exceed captured amount") {
    forAll(genPayment, Gen.listOf(Gen.chooseNum(1L, 20_000L))) { (payment, refundMinorUnits) =>
      val captured = capturedState(payment)
      val finalState =
        refundMinorUnits.zipWithIndex.foldLeft(captured) { case (state, (minorUnits, index)) =>
          val refundAmount = moneyFromMinorUnits(minorUnits, payment.amount.currency)
          val refundCommand =
            PaymentCommand.RefundPayment(
              refundId(index),
              operation(s"refund-bound-$index"),
              refundAmount,
              payment.createdAt
            )

          PaymentDecider.decide(state, refundCommand) match
            case Right(requestedEvents) =>
              val pending = requestedEvents.foldLeft(state)(PaymentDecider.evolve)
              PaymentDecider
                .decide(
                  pending,
                  PaymentCommand
                    .RecordRefundSucceeded(operation(s"refund-bound-$index"), payment.createdAt)
                )
                .fold(_ => pending, _.foldLeft(pending)(PaymentDecider.evolve))
            case Left(_) => state
        }

      totalRefunded(finalState) <= capturedAmount(finalState)
    }
  }

  property("a payment cannot produce more than one logical successful capture event") {
    forAll(genPayment, genProviderOperationId, genProviderOperationId) {
      (payment, firstCapture, secondCapture) =>
        val captured = capturedState(payment, firstCapture)
        val retryResult = PaymentDecider.decide(
          captured,
          PaymentCommand.CapturePayment(secondCapture, payment.createdAt)
        )
        val duplicateSuccess = PaymentDecider.decide(
          captured,
          PaymentCommand.RecordCaptureSucceeded(firstCapture, payment.createdAt)
        )

        retryResult != Right(
          List(PaymentEvent.CaptureRequested(secondCapture, payment.createdAt))
        ) &&
        duplicateSuccess == Right(Nil)
    }
  }

  property("fraud rejected state cannot emit authorization or capture facts") {
    forAll(genPayment, genProviderOperationId) { (payment, operationId) =>
      val rejected = fold(
        PaymentState.NotCreated,
        List(
          createCommand(payment),
          PaymentCommand.StartFraudCheck(payment.createdAt),
          PaymentCommand.RecordFraudRejected(payment.createdAt)
        )
      )

      val commands = List(
        PaymentCommand.AuthorizePayment(operationId, payment.createdAt),
        PaymentCommand.CapturePayment(operationId, payment.createdAt),
        PaymentCommand.RecordAuthorizationSucceeded(operationId, payment.createdAt),
        PaymentCommand.RecordCaptureSucceeded(operationId, payment.createdAt)
      )

      commands.forall(command =>
        acceptedEvents(rejected, command).forall(isNotExternalMutationIntent)
      )
    }
  }

  property("declined state cannot emit captured facts") {
    forAll(genPayment, genProviderOperationId) { (payment, operationId) =>
      val declined = fold(
        PaymentState.NotCreated,
        List(
          createCommand(payment),
          PaymentCommand.StartFraudCheck(payment.createdAt),
          PaymentCommand.RecordFraudApproved(payment.createdAt),
          PaymentCommand.AuthorizePayment(operationId, payment.createdAt),
          PaymentCommand.RecordAuthorizationDeclined(operationId, payment.createdAt)
        )
      )

      acceptedEvents(
        declined,
        PaymentCommand.CapturePayment(operation("capture-after-decline"), payment.createdAt)
      )
        .forall(
          _ != PaymentEvent.PaymentCaptured(operation("capture-after-decline"), payment.createdAt)
        )
    }
  }

  property("unknown states reject fresh mutation retry without resolution") {
    forAll(genPayment) { payment =>
      val authUnknown = authorizationUnknownState(payment)
      val captureUnknown = captureUnknownState(payment)
      val refundUnknown = refundUnknownState(payment)

      PaymentDecider
        .decide(
          authUnknown,
          PaymentCommand.AuthorizePayment(operation("auth-new"), payment.createdAt)
        )
        .isLeft &&
      PaymentDecider
        .decide(
          captureUnknown,
          PaymentCommand.CapturePayment(operation("capture-new"), payment.createdAt)
        )
        .isLeft &&
      PaymentDecider
        .decide(
          refundUnknown,
          PaymentCommand.RefundPayment(
            refundId(999),
            operation("refund-new"),
            payment.amount,
            payment.createdAt
          )
        )
        .isLeft
    }
  }

  property("duplicate external mutation commands do not emit duplicate intent events") {
    forAll(genPayment) { payment =>
      val ready = readyForAuthorization(payment)
      val authCommand = PaymentCommand.AuthorizePayment(operation("auth-dup"), payment.createdAt)
      val authPending = decideAndEvolve(ready, authCommand)

      val authorized = decideAndEvolve(
        authPending,
        PaymentCommand.RecordAuthorizationSucceeded(operation("auth-dup"), payment.createdAt)
      )
      val captureCommand =
        PaymentCommand.CapturePayment(operation("capture-dup"), payment.createdAt)
      val capturePending = decideAndEvolve(authorized, captureCommand)

      val captured = decideAndEvolve(
        capturePending,
        PaymentCommand.RecordCaptureSucceeded(operation("capture-dup"), payment.createdAt)
      )
      val refundCommand =
        PaymentCommand.RefundPayment(
          refundId(1),
          operation("refund-dup"),
          payment.amount,
          payment.createdAt
        )
      val refundPending = decideAndEvolve(captured, refundCommand)

      PaymentDecider.decide(authPending, authCommand) == Right(Nil) &&
      PaymentDecider.decide(capturePending, captureCommand) == Right(Nil) &&
      PaymentDecider.decide(refundPending, refundCommand) == Right(Nil)
    }
  }

  property("events produced by decide can be folded by evolve") {
    forAll(genPayment) { payment =>
      val commands = List(
        createCommand(payment),
        PaymentCommand.StartFraudCheck(payment.createdAt),
        PaymentCommand.RecordFraudApproved(payment.createdAt),
        PaymentCommand.AuthorizePayment(operation("auth-consistency"), payment.createdAt),
        PaymentCommand
          .RecordAuthorizationSucceeded(operation("auth-consistency"), payment.createdAt),
        PaymentCommand.CapturePayment(operation("capture-consistency"), payment.createdAt),
        PaymentCommand.RecordCaptureSucceeded(operation("capture-consistency"), payment.createdAt)
      )

      val finalState = fold(PaymentState.NotCreated, commands)

      extractPayment(finalState) == payment && totalRefunded(finalState) <= capturedAmount(
        finalState
      )
    }
  }

  private val genCurrency: Gen[Currency] =
    Gen.oneOf(Currency.values.toSeq)

  private val genInstant: Gen[Instant] =
    Gen.chooseNum(0L, 4_102_444_800L).map(Instant.ofEpochSecond)

  private val genPaymentId: Gen[PaymentId] =
    Gen.uuid.map(PaymentId.from)

  private val genTenantId: Gen[TenantId] =
    Gen.uuid.map(TenantId.from)

  private val genCustomerId: Gen[CustomerId] =
    Gen.uuid.map(CustomerId.from)

  private val genMerchantId: Gen[MerchantId] =
    Gen.uuid.map(MerchantId.from)

  private val genPaymentMethodToken: Gen[PaymentMethodToken] =
    Gen.uuid.map(uuid => PaymentMethodToken.from(s"tok_$uuid").toOption.get)

  private val genProviderOperationId: Gen[ProviderOperationId] =
    Gen.uuid.map(uuid => ProviderOperationId.from(s"op_$uuid").toOption.get)

  private val genMoney: Gen[Money] =
    for
      currency <- genCurrency
      minorUnits <- Gen.chooseNum(1L, 1_000_000L)
    yield moneyFromMinorUnits(minorUnits, currency)

  private val genPayment: Gen[Payment] =
    for
      paymentId <- genPaymentId
      tenantId <- genTenantId
      customerId <- genCustomerId
      merchantId <- genMerchantId
      amount <- genMoney
      token <- genPaymentMethodToken
      createdAt <- genInstant
    yield Payment(paymentId, tenantId, customerId, merchantId, amount, token, createdAt)

  private def decimalFromMinorUnits(minorUnits: Long, currency: Currency): BigDecimal =
    BigDecimal(java.math.BigDecimal.valueOf(minorUnits, currency.minorUnitScale))

  private def moneyFromMinorUnits(minorUnits: Long, currency: Currency): Money =
    Money.from(decimalFromMinorUnits(minorUnits, currency), currency).toOption.get

  private def effectiveScale(amount: BigDecimal): Int =
    amount.bigDecimal.stripTrailingZeros.scale.max(0)

  private def createCommand(payment: Payment): PaymentCommand.CreatePayment =
    PaymentCommand.CreatePayment(
      payment.paymentId,
      payment.tenantId,
      payment.customerId,
      payment.merchantId,
      payment.amount,
      payment.paymentMethodToken,
      payment.createdAt
    )

  private def readyForAuthorization(payment: Payment): PaymentState =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand(payment),
        PaymentCommand.StartFraudCheck(payment.createdAt),
        PaymentCommand.RecordFraudApproved(payment.createdAt)
      )
    )

  private def capturedState(
      payment: Payment,
      captureOperationId: ProviderOperationId = operation("capture-property")
  ): PaymentState =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand(payment),
        PaymentCommand.StartFraudCheck(payment.createdAt),
        PaymentCommand.RecordFraudApproved(payment.createdAt),
        PaymentCommand.AuthorizePayment(operation("auth-property"), payment.createdAt),
        PaymentCommand.RecordAuthorizationSucceeded(operation("auth-property"), payment.createdAt),
        PaymentCommand.CapturePayment(captureOperationId, payment.createdAt),
        PaymentCommand.RecordCaptureSucceeded(captureOperationId, payment.createdAt)
      )
    )

  private def authorizationUnknownState(payment: Payment): PaymentState =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand(payment),
        PaymentCommand.StartFraudCheck(payment.createdAt),
        PaymentCommand.RecordFraudApproved(payment.createdAt),
        PaymentCommand.AuthorizePayment(operation("auth-unknown"), payment.createdAt),
        PaymentCommand.RecordAuthorizationUnknown(operation("auth-unknown"), payment.createdAt)
      )
    )

  private def captureUnknownState(payment: Payment): PaymentState =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand(payment),
        PaymentCommand.StartFraudCheck(payment.createdAt),
        PaymentCommand.RecordFraudApproved(payment.createdAt),
        PaymentCommand.AuthorizePayment(operation("auth-capture-unknown"), payment.createdAt),
        PaymentCommand
          .RecordAuthorizationSucceeded(operation("auth-capture-unknown"), payment.createdAt),
        PaymentCommand.CapturePayment(operation("capture-unknown"), payment.createdAt),
        PaymentCommand.RecordCaptureUnknown(operation("capture-unknown"), payment.createdAt)
      )
    )

  private def refundUnknownState(payment: Payment): PaymentState =
    fold(
      capturedState(payment),
      List(
        PaymentCommand.RefundPayment(
          refundId(1),
          operation("refund-unknown"),
          payment.amount,
          payment.createdAt
        ),
        PaymentCommand.RecordRefundUnknown(operation("refund-unknown"), payment.createdAt)
      )
    )

  private def fold(initial: PaymentState, commands: List[PaymentCommand]): PaymentState =
    commands.foldLeft(initial) { case (state, command) =>
      decideAndEvolve(state, command)
    }

  private def decideAndEvolve(state: PaymentState, command: PaymentCommand): PaymentState =
    PaymentDecider.decide(state, command) match
      case Right(events) => events.foldLeft(state)(PaymentDecider.evolve)
      case Left(error) => fail(s"Unexpected rejection: $error")

  private def acceptedEvents(state: PaymentState, command: PaymentCommand): List[PaymentEvent] =
    PaymentDecider.decide(state, command).getOrElse(Nil)

  private def isNotExternalMutationIntent(event: PaymentEvent): Boolean =
    event match
      case _: PaymentEvent.AuthorizationRequested => false
      case _: PaymentEvent.PaymentAuthorized => false
      case _: PaymentEvent.CaptureRequested => false
      case _: PaymentEvent.PaymentCaptured => false
      case _ => true

  private def extractPayment(state: PaymentState): Payment =
    state match
      case PaymentState.Created(payment) => payment
      case PaymentState.FraudCheckPending(payment) => payment
      case PaymentState.FraudRejected(payment) => payment
      case PaymentState.ManualReview(payment) => payment
      case PaymentState.ReadyForAuthorization(payment) => payment
      case PaymentState.AuthorizationPending(payment, _) => payment
      case PaymentState.Authorized(payment, _) => payment
      case PaymentState.Declined(payment, _) => payment
      case PaymentState.AuthorizationUnknown(payment, _) => payment
      case PaymentState.CapturePending(payment, _, _) => payment
      case PaymentState.CaptureFailed(payment, _, _) => payment
      case PaymentState.CaptureUnknown(payment, _, _) => payment
      case PaymentState.Captured(payment, _, _, _) => payment
      case PaymentState.RefundPending(payment, _, _, _, _) => payment
      case PaymentState.PartiallyRefunded(payment, _, _, _) => payment
      case PaymentState.RefundFailed(payment, _, _, _, _) => payment
      case PaymentState.RefundUnknown(payment, _, _, _, _) => payment
      case PaymentState.Refunded(payment, _, _, _) => payment
      case PaymentState.NotCreated => fail("NotCreated does not contain payment data")

  private def totalRefunded(state: PaymentState): BigDecimal =
    state match
      case PaymentState.Captured(_, _, _, refunds) => refunds.map(_.amount.amount).sum
      case PaymentState.PartiallyRefunded(_, _, _, refunds) => refunds.map(_.amount.amount).sum
      case PaymentState.Refunded(_, _, _, refunds) => refunds.map(_.amount.amount).sum
      case PaymentState.RefundPending(_, _, _, refunds, _) => refunds.map(_.amount.amount).sum
      case PaymentState.RefundUnknown(_, _, _, refunds, _) => refunds.map(_.amount.amount).sum
      case PaymentState.RefundFailed(_, _, _, refunds, _) => refunds.map(_.amount.amount).sum
      case _ => BigDecimal(0)

  private def capturedAmount(state: PaymentState): BigDecimal =
    state match
      case PaymentState.Captured(_, _, capture, _) => capture.amount.amount
      case PaymentState.PartiallyRefunded(_, _, capture, _) => capture.amount.amount
      case PaymentState.Refunded(_, _, capture, _) => capture.amount.amount
      case PaymentState.RefundPending(_, _, capture, _, _) => capture.amount.amount
      case PaymentState.RefundUnknown(_, _, capture, _, _) => capture.amount.amount
      case PaymentState.RefundFailed(_, _, capture, _, _) => capture.amount.amount
      case _ => BigDecimal(Long.MaxValue)

  private def refundId(index: Int): RefundId =
    RefundId.from(
      UUID.nameUUIDFromBytes(s"refund-$index".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )

  private def operation(value: String): ProviderOperationId =
    ProviderOperationId.from(value).toOption.get
