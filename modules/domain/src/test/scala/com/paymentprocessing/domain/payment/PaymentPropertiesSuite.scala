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

  property("mutated refund event histories are rejected during replay") {
    forAll(genPayment) { payment =>
      val captured = capturedState(payment)
      val refundIdValue = refundId(50)
      val operationId = operation("refund-history-mutation")
      val pending = decideAndEvolve(
        captured,
        PaymentCommand.RefundPayment(refundIdValue, operationId, payment.amount, payment.createdAt)
      )
      val wrongAmount = incrementByMinorUnit(payment.amount)

      val mutatedEvents = List(
        PaymentEvent.PaymentRefunded(refundId(51), operationId, payment.amount, payment.createdAt),
        PaymentEvent.PaymentRefunded(
          refundIdValue,
          operation("refund-history-other"),
          payment.amount,
          payment.createdAt
        ),
        PaymentEvent.PaymentRefunded(refundIdValue, operationId, wrongAmount, payment.createdAt),
        PaymentEvent.PaymentPartiallyRefunded(
          refundIdValue,
          operationId,
          payment.amount,
          payment.createdAt
        )
      )

      mutatedEvents.forall(event => throwsInvalidHistory(pending, event))
    }
  }

  property("state-aware generated lifecycle traces preserve invariants after every step") {
    forAll(genPaymentTrace) { case (payment, commands) =>
      validateGeneratedTrace(payment, commands)
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

  private val genPaymentTrace: Gen[(Payment, List[PaymentCommand])] =
    genPayment.flatMap(payment => genLegalTrace(payment).map(commands => (payment, commands)))

  private def genLegalTrace(payment: Payment): Gen[List[PaymentCommand]] =
    def loop(state: PaymentState, index: Int, remaining: Int): Gen[List[PaymentCommand]] =
      if remaining <= 0 then Gen.const(Nil)
      else
        genNextCommand(payment, state, index).flatMap {
          case None => Gen.const(Nil)
          case Some(command) =>
            val nextState = PaymentDecider.decide(state, command) match
              case Right(events) => events.foldLeft(state)(PaymentDecider.evolve)
              case Left(_) => state

            loop(nextState, index + 1, remaining - 1).map(command :: _)
        }

    loop(PaymentState.NotCreated, index = 0, remaining = 32)

  private def genNextCommand(
      payment: Payment,
      state: PaymentState,
      index: Int
  ): Gen[Option[PaymentCommand]] =
    state match
      case PaymentState.NotCreated =>
        Gen.const(Some(createCommand(payment)))
      case PaymentState.Created(_) =>
        Gen.const(Some(PaymentCommand.StartFraudCheck(payment.createdAt)))
      case PaymentState.FraudCheckPending(_) =>
        Gen
          .oneOf(
            PaymentCommand.RecordFraudApproved(payment.createdAt),
            PaymentCommand.RecordFraudRejected(payment.createdAt),
            PaymentCommand.RecordFraudManualReview(payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.ManualReview(_) =>
        Gen
          .oneOf(
            PaymentCommand.ApproveManualReview(payment.createdAt),
            PaymentCommand.RejectManualReview(payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.ReadyForAuthorization(_) =>
        Gen.const(
          Some(PaymentCommand.AuthorizePayment(operation(s"trace-auth-$index"), payment.createdAt))
        )
      case PaymentState.AuthorizationPending(_, operationId) =>
        Gen
          .oneOf(
            PaymentCommand.AuthorizePayment(operationId, payment.createdAt),
            PaymentCommand.RecordAuthorizationSucceeded(operationId, payment.createdAt),
            PaymentCommand.RecordAuthorizationDeclined(operationId, payment.createdAt),
            PaymentCommand.RecordAuthorizationUnknown(operationId, payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.AuthorizationUnknown(_, operationId) =>
        Gen
          .oneOf(
            PaymentCommand.ResolveAuthorizationUnknownAsSucceeded(operationId, payment.createdAt),
            PaymentCommand.ResolveAuthorizationUnknownAsDeclined(operationId, payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.Authorized(_, _) =>
        Gen.const(
          Some(PaymentCommand.CapturePayment(operation(s"trace-capture-$index"), payment.createdAt))
        )
      case PaymentState.CapturePending(_, _, operationId) =>
        Gen
          .oneOf(
            PaymentCommand.CapturePayment(operationId, payment.createdAt),
            PaymentCommand.RecordCaptureSucceeded(operationId, payment.createdAt),
            PaymentCommand.RecordCaptureFailed(operationId, payment.createdAt),
            PaymentCommand.RecordCaptureUnknown(operationId, payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.CaptureUnknown(_, _, operationId) =>
        Gen
          .oneOf(
            PaymentCommand.ResolveCaptureUnknownAsSucceeded(operationId, payment.createdAt),
            PaymentCommand.ResolveCaptureUnknownAsFailed(operationId, payment.createdAt)
          )
          .map(Some(_))
      case state: (PaymentState.Captured | PaymentState.PartiallyRefunded) =>
        genRefundAmount(state).map(amount =>
          Some(
            PaymentCommand.RefundPayment(
              refundId(index),
              operation(s"trace-refund-$index"),
              amount,
              payment.createdAt
            )
          )
        )
      case PaymentState.RefundPending(_, _, _, _, pending) =>
        Gen
          .oneOf(
            PaymentCommand.RefundPayment(
              pending.refundId,
              pending.operationId,
              pending.amount,
              payment.createdAt
            ),
            PaymentCommand.RecordRefundSucceeded(pending.operationId, payment.createdAt),
            PaymentCommand.RecordRefundFailed(pending.operationId, payment.createdAt),
            PaymentCommand.RecordRefundUnknown(pending.operationId, payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.RefundUnknown(_, _, _, _, pending) =>
        Gen
          .oneOf(
            PaymentCommand.ResolveRefundUnknownAsSucceeded(pending.operationId, payment.createdAt),
            PaymentCommand.ResolveRefundUnknownAsFailed(pending.operationId, payment.createdAt)
          )
          .map(Some(_))
      case PaymentState.FraudRejected(_) | PaymentState.Declined(_, _) |
          PaymentState.CaptureFailed(_, _, _) | PaymentState.RefundFailed(_, _, _, _, _) |
          PaymentState.Refunded(_, _, _, _) =>
        Gen.const(None)

  private def decimalFromMinorUnits(minorUnits: Long, currency: Currency): BigDecimal =
    BigDecimal(java.math.BigDecimal.valueOf(minorUnits, currency.minorUnitScale))

  private def moneyFromMinorUnits(minorUnits: Long, currency: Currency): Money =
    Money.from(decimalFromMinorUnits(minorUnits, currency), currency).toOption.get

  private def genRefundAmount(
      state: PaymentState.Captured | PaymentState.PartiallyRefunded
  ): Gen[Money] =
    val (capture, refunds) =
      state match
        case PaymentState.Captured(_, _, capture, refunds) => (capture, refunds)
        case PaymentState.PartiallyRefunded(_, _, capture, refunds) => (capture, refunds)

    val refunded = refunds.map(_.amount.amount).sum
    val remaining = capture.amount.amount - refunded
    val oneMinor = decimalFromMinorUnits(1L, capture.amount.currency)
    val smallest = Money.from(oneMinor.min(remaining), capture.amount.currency).toOption.get
    val full = Money.from(remaining, capture.amount.currency).toOption.get

    if smallest == full then Gen.const(full)
    else Gen.oneOf(smallest, full)

  private def incrementByMinorUnit(money: Money): Money =
    Money
      .from(money.amount + decimalFromMinorUnits(1L, money.currency), money.currency)
      .toOption
      .get

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

  private def throwsInvalidHistory(state: PaymentState, event: PaymentEvent): Boolean =
    try
      val _ = PaymentDecider.evolve(state, event)
      false
    catch case _: InvalidPaymentHistory => true

  private def validateGeneratedTrace(payment: Payment, commands: List[PaymentCommand]): Boolean =
    final case class TraceCheck(
        state: PaymentState,
        fraudRejectedSeen: Boolean,
        declinedSeen: Boolean,
        valid: Boolean
    )

    val result = commands.foldLeft(TraceCheck(PaymentState.NotCreated, false, false, true)) {
      case (check, command) if check.valid =>
        PaymentDecider.decide(check.state, command) match
          case Right(events) =>
            val after = events.foldLeft(check.state)(PaymentDecider.evolve)
            val fraudRejectedSeen =
              check.fraudRejectedSeen || check.state.isInstanceOf[PaymentState.FraudRejected] ||
                after.isInstanceOf[PaymentState.FraudRejected]
            val declinedSeen =
              check.declinedSeen || check.state.isInstanceOf[PaymentState.Declined] ||
                after.isInstanceOf[PaymentState.Declined]

            TraceCheck(
              after,
              fraudRejectedSeen,
              declinedSeen,
              stateInvariantsHold(payment, after) &&
                !(fraudRejectedSeen && isAuthorizedOrLater(after)) &&
                !(declinedSeen && isCapturedOrLater(after))
            )
          case Left(_) =>
            check.copy(valid = false)
      case (check, _) => check
    }

    result.valid

  private def stateInvariantsHold(payment: Payment, state: PaymentState): Boolean =
    paymentInState(state).forall(_ == payment) &&
      totalRefunded(state) <= capturedAmount(state) &&
      completedRefundIds(state).distinct.size == completedRefundIds(state).size &&
      providerOperationIds(state).distinct.size == providerOperationIds(state).size &&
      unknownStateRejectsFreshMutation(state, payment)

  private def paymentInState(state: PaymentState): Option[Payment] =
    state match
      case PaymentState.NotCreated => None
      case _ => Some(extractPayment(state))

  private def completedRefundIds(state: PaymentState): List[RefundId] =
    completedRefunds(state).map(_.refundId)

  private def providerOperationIds(state: PaymentState): List[ProviderOperationId] =
    state match
      case PaymentState.AuthorizationPending(_, operationId) => List(operationId)
      case PaymentState.Authorized(_, authorization) => List(authorization.operationId)
      case PaymentState.Declined(_, authorization) => List(authorization.operationId)
      case PaymentState.AuthorizationUnknown(_, operationId) => List(operationId)
      case PaymentState.CapturePending(_, authorization, operationId) =>
        List(authorization.operationId, operationId)
      case PaymentState.CaptureFailed(_, authorization, operationId) =>
        List(authorization.operationId, operationId)
      case PaymentState.CaptureUnknown(_, authorization, operationId) =>
        List(authorization.operationId, operationId)
      case PaymentState.Captured(_, authorization, capture, refunds) =>
        authorization.operationId :: capture.operationId :: refunds.map(_.operationId)
      case PaymentState.RefundPending(_, authorization, capture, refunds, pending) =>
        authorization.operationId :: capture.operationId :: pending.operationId :: refunds.map(
          _.operationId
        )
      case PaymentState.PartiallyRefunded(_, authorization, capture, refunds) =>
        authorization.operationId :: capture.operationId :: refunds.map(_.operationId)
      case PaymentState.RefundFailed(_, authorization, capture, refunds, failed) =>
        authorization.operationId :: capture.operationId :: failed.operationId :: refunds.map(
          _.operationId
        )
      case PaymentState.RefundUnknown(_, authorization, capture, refunds, unknown) =>
        authorization.operationId :: capture.operationId :: unknown.operationId :: refunds.map(
          _.operationId
        )
      case PaymentState.Refunded(_, authorization, capture, refunds) =>
        authorization.operationId :: capture.operationId :: refunds.map(_.operationId)
      case _ => Nil

  private def completedRefunds(state: PaymentState): List[RefundRecord] =
    state match
      case PaymentState.Captured(_, _, _, refunds) => refunds
      case PaymentState.PartiallyRefunded(_, _, _, refunds) => refunds
      case PaymentState.Refunded(_, _, _, refunds) => refunds
      case PaymentState.RefundPending(_, _, _, refunds, _) => refunds
      case PaymentState.RefundUnknown(_, _, _, refunds, _) => refunds
      case PaymentState.RefundFailed(_, _, _, refunds, _) => refunds
      case _ => Nil

  private def unknownStateRejectsFreshMutation(state: PaymentState, payment: Payment): Boolean =
    state match
      case PaymentState.AuthorizationUnknown(_, _) =>
        PaymentDecider
          .decide(
            state,
            PaymentCommand.AuthorizePayment(operation("trace-fresh-auth"), payment.createdAt)
          )
          .isLeft
      case PaymentState.CaptureUnknown(_, _, _) =>
        PaymentDecider
          .decide(
            state,
            PaymentCommand.CapturePayment(operation("trace-fresh-capture"), payment.createdAt)
          )
          .isLeft
      case PaymentState.RefundUnknown(_, _, _, _, _) =>
        PaymentDecider
          .decide(
            state,
            PaymentCommand.RefundPayment(
              refundId(9000),
              operation("trace-fresh-refund"),
              payment.amount,
              payment.createdAt
            )
          )
          .isLeft
      case _ => true

  private def isAuthorizedOrLater(state: PaymentState): Boolean =
    state match
      case PaymentState.Authorized(_, _) | PaymentState.CapturePending(_, _, _) |
          PaymentState.CaptureFailed(_, _, _) | PaymentState.CaptureUnknown(_, _, _) |
          PaymentState.Captured(_, _, _, _) | PaymentState.RefundPending(_, _, _, _, _) |
          PaymentState.PartiallyRefunded(_, _, _, _) | PaymentState.RefundFailed(_, _, _, _, _) |
          PaymentState.RefundUnknown(_, _, _, _, _) | PaymentState.Refunded(_, _, _, _) =>
        true
      case _ => false

  private def isCapturedOrLater(state: PaymentState): Boolean =
    state match
      case PaymentState.Captured(_, _, _, _) | PaymentState.RefundPending(_, _, _, _, _) |
          PaymentState.PartiallyRefunded(_, _, _, _) | PaymentState.RefundFailed(_, _, _, _, _) |
          PaymentState.RefundUnknown(_, _, _, _, _) | PaymentState.Refunded(_, _, _, _) =>
        true
      case _ => false

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
