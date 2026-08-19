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

final class PaymentDeciderSuite extends FunSuite:
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  test("create freezes payment core data") {
    val event = decideOne(PaymentState.NotCreated, createCommand)
    val nextState = PaymentDecider.evolve(PaymentState.NotCreated, event)

    assertEquals(event.productPrefix, "PaymentCreated")
    assertEquals(nextState, PaymentState.Created(payment))
  }

  test("fraud approved moves payment to ready for authorization") {
    val state =
      fold(PaymentState.NotCreated, List(createCommand, PaymentCommand.StartFraudCheck(now)))

    val event = decideOne(state, PaymentCommand.RecordFraudApproved(now))

    assertEquals(PaymentDecider.evolve(state, event), PaymentState.ReadyForAuthorization(payment))
  }

  test("fraud rejected is terminal for authorization") {
    val state = fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudRejected(now)
      )
    )

    assertEquals(
      PaymentDecider.decide(state, PaymentCommand.AuthorizePayment(operation("auth-1"), now)),
      Left(PaymentError.FraudRejected)
    )
  }

  test("manual review approval and rejection produce definitive fraud states") {
    val manualReview = fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudManualReview(now)
      )
    )

    assertEquals(
      PaymentDecider
        .evolve(manualReview, decideOne(manualReview, PaymentCommand.ApproveManualReview(now))),
      PaymentState.ReadyForAuthorization(payment)
    )
    assertEquals(
      PaymentDecider
        .evolve(manualReview, decideOne(manualReview, PaymentCommand.RejectManualReview(now))),
      PaymentState.FraudRejected(payment)
    )
  }

  test("authorization success, decline and unknown reconciliation are operation-correlated") {
    val ready = readyForAuthorization
    val pending = PaymentDecider.evolve(
      ready,
      decideOne(ready, PaymentCommand.AuthorizePayment(operation("auth-1"), now))
    )

    assertEquals(
      PaymentDecider
        .evolve(
          pending,
          decideOne(pending, PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now))
        )
        .productPrefix,
      "Authorized"
    )
    assertEquals(
      PaymentDecider
        .evolve(
          pending,
          decideOne(pending, PaymentCommand.RecordAuthorizationDeclined(operation("auth-1"), now))
        )
        .productPrefix,
      "Declined"
    )

    val unknown =
      PaymentDecider.evolve(
        pending,
        decideOne(pending, PaymentCommand.RecordAuthorizationUnknown(operation("auth-1"), now))
      )

    assertEquals(
      PaymentDecider.decide(unknown, PaymentCommand.AuthorizePayment(operation("auth-2"), now)),
      Left(PaymentError.OperationMismatch(operation("auth-1"), operation("auth-2")))
    )
    assertEquals(
      PaymentDecider
        .evolve(
          unknown,
          decideOne(
            unknown,
            PaymentCommand.ResolveAuthorizationUnknownAsSucceeded(operation("auth-1"), now)
          )
        )
        .productPrefix,
      "Authorized"
    )
  }

  test("capture success, failure and unknown reconciliation are operation-correlated") {
    val authorized = authorizedState
    val pending = PaymentDecider.evolve(
      authorized,
      decideOne(authorized, PaymentCommand.CapturePayment(operation("capture-1"), now))
    )

    assertEquals(
      PaymentDecider
        .evolve(
          pending,
          decideOne(pending, PaymentCommand.RecordCaptureSucceeded(operation("capture-1"), now))
        )
        .productPrefix,
      "Captured"
    )
    assertEquals(
      PaymentDecider
        .evolve(
          pending,
          decideOne(pending, PaymentCommand.RecordCaptureFailed(operation("capture-1"), now))
        )
        .productPrefix,
      "CaptureFailed"
    )

    val unknown =
      PaymentDecider.evolve(
        pending,
        decideOne(pending, PaymentCommand.RecordCaptureUnknown(operation("capture-1"), now))
      )

    assertEquals(
      PaymentDecider.decide(unknown, PaymentCommand.CapturePayment(operation("capture-2"), now)),
      Left(PaymentError.OperationMismatch(operation("capture-1"), operation("capture-2")))
    )
    assertEquals(
      PaymentDecider
        .evolve(
          unknown,
          decideOne(
            unknown,
            PaymentCommand.ResolveCaptureUnknownAsSucceeded(operation("capture-1"), now)
          )
        )
        .productPrefix,
      "Captured"
    )
  }

  test("refund supports partial, multiple partial, full and unknown reconciliation") {
    val captured = capturedState
    val partialPending =
      PaymentDecider.evolve(
        captured,
        decideOne(
          captured,
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("30.00"), now)
        )
      )
    val partiallyRefunded =
      PaymentDecider.evolve(
        partialPending,
        decideOne(partialPending, PaymentCommand.RecordRefundSucceeded(operation("refund-1"), now))
      )

    assertEquals(partiallyRefunded.productPrefix, "PartiallyRefunded")

    val secondPending =
      PaymentDecider.evolve(
        partiallyRefunded,
        decideOne(
          partiallyRefunded,
          PaymentCommand.RefundPayment(refundId(2), operation("refund-2"), money("20.00"), now)
        )
      )
    val secondPartial =
      PaymentDecider.evolve(
        secondPending,
        decideOne(secondPending, PaymentCommand.RecordRefundSucceeded(operation("refund-2"), now))
      )

    assertEquals(secondPartial.productPrefix, "PartiallyRefunded")

    val unknownPending =
      PaymentDecider.evolve(
        secondPartial,
        decideOne(
          secondPartial,
          PaymentCommand.RefundPayment(refundId(3), operation("refund-3"), money("50.00"), now)
        )
      )
    val unknown =
      PaymentDecider.evolve(
        unknownPending,
        decideOne(unknownPending, PaymentCommand.RecordRefundUnknown(operation("refund-3"), now))
      )

    assertEquals(
      PaymentDecider.decide(
        unknown,
        PaymentCommand.RefundPayment(refundId(4), operation("refund-4"), money("1.00"), now)
      ),
      Left(PaymentError.OperationMismatch(operation("refund-3"), operation("refund-4")))
    )
    assertEquals(
      PaymentDecider
        .evolve(
          unknown,
          decideOne(
            unknown,
            PaymentCommand.ResolveRefundUnknownAsSucceeded(operation("refund-3"), now)
          )
        )
        .productPrefix,
      "Refunded"
    )
  }

  test("decide does not mutate input state") {
    val before = readyForAuthorization

    val _ = PaymentDecider.decide(before, PaymentCommand.AuthorizePayment(operation("auth-1"), now))

    assertEquals(before, readyForAuthorization)
  }

  test("folding produced events reconstructs deterministic state") {
    val commands = List(
      createCommand,
      PaymentCommand.StartFraudCheck(now),
      PaymentCommand.RecordFraudApproved(now),
      PaymentCommand.AuthorizePayment(operation("auth-1"), now),
      PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now),
      PaymentCommand.CapturePayment(operation("capture-1"), now),
      PaymentCommand.RecordCaptureSucceeded(operation("capture-1"), now)
    )

    assertEquals(fold(PaymentState.NotCreated, commands), capturedState)
  }

  private def decideOne(state: PaymentState, command: PaymentCommand): PaymentEvent =
    PaymentDecider.decide(state, command) match
      case Right(List(event)) => event
      case Right(events) => fail(s"Expected one event, got $events")
      case Left(error) => fail(s"Expected accepted command, got $error")

  private def fold(initial: PaymentState, commands: List[PaymentCommand]): PaymentState =
    commands.foldLeft(initial) { case (state, command) =>
      PaymentDecider.decide(state, command) match
        case Right(events) => events.foldLeft(state)(PaymentDecider.evolve)
        case Left(error) => fail(s"Unexpected rejection: $error")
    }

  private def readyForAuthorization =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudApproved(now)
      )
    )

  private def authorizedState =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudApproved(now),
        PaymentCommand.AuthorizePayment(operation("auth-1"), now),
        PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
      )
    )

  private def capturedState =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudApproved(now),
        PaymentCommand.AuthorizePayment(operation("auth-1"), now),
        PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now),
        PaymentCommand.CapturePayment(operation("capture-1"), now),
        PaymentCommand.RecordCaptureSucceeded(operation("capture-1"), now)
      )
    )

  private def createCommand =
    PaymentCommand.CreatePayment(
      payment.paymentId,
      payment.tenantId,
      payment.customerId,
      payment.merchantId,
      payment.amount,
      payment.paymentMethodToken,
      payment.createdAt
    )

  private def payment =
    Payment(
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000201")),
      TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000202")),
      CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000203")),
      MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000204")),
      money("100.00"),
      PaymentMethodToken.from("tok_decider_1").fold(error => fail(error.toString), identity),
      now
    )

  private def refundId(index: Int) =
    RefundId.from(UUID.fromString(f"00000000-0000-0000-0000-$index%012d"))

  private def operation(value: String) =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)

  private def money(value: String) =
    Money.from(BigDecimal(value), Currency.PLN).fold(error => fail(error.toString), identity)
