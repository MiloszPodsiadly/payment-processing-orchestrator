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

final class PaymentTransitionMatrixSuite extends FunSuite:
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  test("transition matrix has explicit fixture coverage for every state") {
    val stateNames = allStates.map(_.productPrefix).toSet

    assertEquals(stateNames, PaymentTransitionMatrixSuite.fixtureStateLabels)
  }

  test("illegal lifecycle transitions return exact typed errors") {
    val cases = List(
      (
        PaymentState.NotCreated,
        PaymentCommand.AuthorizePayment(op("auth"), now),
        PaymentError.PaymentNotCreated
      ),
      (
        PaymentState.NotCreated,
        PaymentCommand.CapturePayment(op("capture"), now),
        PaymentError.PaymentNotCreated
      ),
      (
        PaymentState.NotCreated,
        PaymentCommand.RefundPayment(refundId(1), op("refund"), money("1.00"), now),
        PaymentError.PaymentNotCreated
      ),
      (
        created,
        PaymentCommand.CapturePayment(op("capture"), now),
        PaymentError.InvalidStateTransition
      ),
      (
        created,
        PaymentCommand.RefundPayment(refundId(1), op("refund"), money("1.00"), now),
        PaymentError.InvalidStateTransition
      ),
      (
        fraudPending,
        PaymentCommand.AuthorizePayment(op("auth"), now),
        PaymentError.InvalidStateTransition
      ),
      (fraudRejected, PaymentCommand.AuthorizePayment(op("auth"), now), PaymentError.FraudRejected),
      (
        fraudRejected,
        PaymentCommand.CapturePayment(op("capture"), now),
        PaymentError.InvalidStateTransition
      ),
      (
        manualReview,
        PaymentCommand.CapturePayment(op("capture"), now),
        PaymentError.InvalidStateTransition
      ),
      (
        readyForAuthorization,
        PaymentCommand.CapturePayment(op("capture"), now),
        PaymentError.InvalidStateTransition
      ),
      (
        authorizationPending,
        PaymentCommand.AuthorizePayment(op("auth-other"), now),
        PaymentError.OperationMismatch(op("auth-1"), op("auth-other"))
      ),
      (declined, PaymentCommand.CapturePayment(op("capture"), now), PaymentError.PaymentDeclined),
      (
        authorizationUnknown,
        PaymentCommand.AuthorizePayment(op("auth-other"), now),
        PaymentError.OperationMismatch(op("auth-1"), op("auth-other"))
      ),
      (
        authorized,
        PaymentCommand.RefundPayment(refundId(1), op("refund"), money("1.00"), now),
        PaymentError.PaymentNotCaptured
      ),
      (
        capturePending,
        PaymentCommand.CapturePayment(op("capture-other"), now),
        PaymentError.OperationMismatch(op("capture-1"), op("capture-other"))
      ),
      (
        captureUnknown,
        PaymentCommand.CapturePayment(op("capture-other"), now),
        PaymentError.OperationMismatch(op("capture-1"), op("capture-other"))
      ),
      (
        captured,
        PaymentCommand.CapturePayment(op("capture-other"), now),
        PaymentError.PaymentAlreadyCaptured
      ),
      (
        refundPending,
        PaymentCommand.RefundPayment(refundId(1), op("refund-1"), money("2.00"), now),
        PaymentError.DuplicateRefundConflict(refundId(1))
      ),
      (
        refundUnknown,
        PaymentCommand.RefundPayment(refundId(2), op("refund-other"), money("1.00"), now),
        PaymentError.OperationMismatch(op("refund-1"), op("refund-other"))
      ),
      (
        refunded,
        PaymentCommand.RefundPayment(refundId(9), op("refund-9"), money("1.00"), now),
        PaymentError.PaymentAlreadyRefunded
      )
    )

    cases.foreach { case (state, command, expectedError) =>
      assertEquals(PaymentDecider.decide(state, command), Left(expectedError))
    }
  }

  test("decision command coverage catalog is synchronized with the command ADT") {
    assertEquals(
      PaymentTransitionMatrixSuite.decisionCommandLabels,
      PaymentAdtInventory.commandLabels
    )
  }

  test("replay event coverage catalog is synchronized with the event ADT") {
    assertEquals(PaymentTransitionMatrixSuite.replayEventLabels, PaymentAdtInventory.eventLabels)
  }

  test("wrong provider operation result is rejected in every pending or unknown state") {
    val cases = List(
      (
        authorizationPending,
        PaymentCommand.RecordAuthorizationSucceeded(op("auth-wrong"), now),
        PaymentError.OperationMismatch(op("auth-1"), op("auth-wrong"))
      ),
      (
        authorizationUnknown,
        PaymentCommand.ResolveAuthorizationUnknownAsSucceeded(op("auth-wrong"), now),
        PaymentError.OperationMismatch(op("auth-1"), op("auth-wrong"))
      ),
      (
        capturePending,
        PaymentCommand.RecordCaptureSucceeded(op("capture-wrong"), now),
        PaymentError.OperationMismatch(op("capture-1"), op("capture-wrong"))
      ),
      (
        captureUnknown,
        PaymentCommand.ResolveCaptureUnknownAsSucceeded(op("capture-wrong"), now),
        PaymentError.OperationMismatch(op("capture-1"), op("capture-wrong"))
      ),
      (
        refundPending,
        PaymentCommand.RecordRefundSucceeded(op("refund-wrong"), now),
        PaymentError.OperationMismatch(op("refund-1"), op("refund-wrong"))
      ),
      (
        refundUnknown,
        PaymentCommand.ResolveRefundUnknownAsSucceeded(op("refund-wrong"), now),
        PaymentError.OperationMismatch(op("refund-1"), op("refund-wrong"))
      )
    )

    cases.foreach { case (state, command, expectedError) =>
      assertEquals(PaymentDecider.decide(state, command), Left(expectedError))
    }
  }

  test("duplicate results are no-op only for the same already-applied outcome") {
    assertEquals(
      PaymentDecider
        .decide(authorized, PaymentCommand.RecordAuthorizationSucceeded(op("auth-1"), now)),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider
        .decide(declined, PaymentCommand.RecordAuthorizationDeclined(op("auth-1"), now)),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider.decide(captured, PaymentCommand.RecordCaptureSucceeded(op("capture-1"), now)),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider
        .decide(partiallyRefunded, PaymentCommand.RecordRefundSucceeded(op("refund-1"), now)),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider
        .decide(authorized, PaymentCommand.RecordAuthorizationDeclined(op("auth-1"), now)),
      Left(PaymentError.ConflictingOperationOutcome(op("auth-1")))
    )
    assertEquals(
      PaymentDecider.decide(captured, PaymentCommand.RecordCaptureFailed(op("capture-1"), now)),
      Left(PaymentError.ConflictingOperationOutcome(op("capture-1")))
    )
    assertEquals(
      PaymentDecider
        .decide(partiallyRefunded, PaymentCommand.RecordRefundFailed(op("refund-1"), now)),
      Left(PaymentError.ConflictingOperationOutcome(op("refund-1")))
    )
  }

  test("out-of-order provider results do not corrupt state") {
    val cases = List(
      (readyForAuthorization, PaymentCommand.RecordAuthorizationSucceeded(op("auth-1"), now)),
      (authorized, PaymentCommand.RecordCaptureSucceeded(op("capture-1"), now)),
      (captured, PaymentCommand.RecordRefundSucceeded(op("refund-1"), now)),
      (captured, PaymentCommand.RecordAuthorizationSucceeded(op("auth-1"), now)),
      (refundPending, PaymentCommand.RecordRefundSucceeded(op("refund-old"), now))
    )

    cases.foreach { case (state, command) =>
      assert(PaymentDecider.decide(state, command).isLeft)
    }
  }

  test("corrupt event history fails loudly during evolve") {
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        PaymentState.NotCreated,
        PaymentEvent.PaymentCaptured(op("capture-1"), now)
      )
    }
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        created,
        PaymentEvent.PaymentRefunded(refundId(1), op("refund-1"), money("1.00"), now)
      )
    }
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(fraudRejected, PaymentEvent.PaymentAuthorized(op("auth-1"), now))
    }
  }

  private def allStates: List[PaymentState] =
    List(
      PaymentState.NotCreated,
      created,
      fraudPending,
      fraudRejected,
      manualReview,
      readyForAuthorization,
      authorizationPending,
      authorized,
      declined,
      authorizationUnknown,
      capturePending,
      captureFailed,
      captureUnknown,
      captured,
      refundPending,
      partiallyRefunded,
      refundFailed,
      refundUnknown,
      refunded
    )

  private def created =
    fold(PaymentState.NotCreated, List(createCommand))

  private def fraudPending =
    fold(PaymentState.NotCreated, List(createCommand, PaymentCommand.StartFraudCheck(now)))

  private def fraudRejected =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudRejected(now)
      )
    )

  private def manualReview =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudManualReview(now)
      )
    )

  private def readyForAuthorization =
    fold(
      PaymentState.NotCreated,
      List(
        createCommand,
        PaymentCommand.StartFraudCheck(now),
        PaymentCommand.RecordFraudApproved(now)
      )
    )

  private def authorizationPending =
    fold(readyForAuthorization, List(PaymentCommand.AuthorizePayment(op("auth-1"), now)))

  private def authorized =
    fold(authorizationPending, List(PaymentCommand.RecordAuthorizationSucceeded(op("auth-1"), now)))

  private def declined =
    fold(authorizationPending, List(PaymentCommand.RecordAuthorizationDeclined(op("auth-1"), now)))

  private def authorizationUnknown =
    fold(authorizationPending, List(PaymentCommand.RecordAuthorizationUnknown(op("auth-1"), now)))

  private def capturePending =
    fold(authorized, List(PaymentCommand.CapturePayment(op("capture-1"), now)))

  private def captureFailed =
    fold(capturePending, List(PaymentCommand.RecordCaptureFailed(op("capture-1"), now)))

  private def captureUnknown =
    fold(capturePending, List(PaymentCommand.RecordCaptureUnknown(op("capture-1"), now)))

  private def captured =
    fold(capturePending, List(PaymentCommand.RecordCaptureSucceeded(op("capture-1"), now)))

  private def refundPending =
    fold(
      captured,
      List(PaymentCommand.RefundPayment(refundId(1), op("refund-1"), money("1.00"), now))
    )

  private def partiallyRefunded =
    fold(refundPending, List(PaymentCommand.RecordRefundSucceeded(op("refund-1"), now)))

  private def refundFailed =
    fold(refundPending, List(PaymentCommand.RecordRefundFailed(op("refund-1"), now)))

  private def refundUnknown =
    fold(refundPending, List(PaymentCommand.RecordRefundUnknown(op("refund-1"), now)))

  private def refunded =
    val pending =
      fold(
        captured,
        List(PaymentCommand.RefundPayment(refundId(2), op("refund-2"), money("100.00"), now))
      )

    fold(pending, List(PaymentCommand.RecordRefundSucceeded(op("refund-2"), now)))

  private def fold(initial: PaymentState, commands: List[PaymentCommand]): PaymentState =
    commands.foldLeft(initial) { case (state, command) =>
      PaymentDecider.decide(state, command) match
        case Right(events) => events.foldLeft(state)(PaymentDecider.evolve)
        case Left(error) => fail(s"Unexpected rejection: $error")
    }

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
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000401")),
      TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000402")),
      CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000403")),
      MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000404")),
      money("100.00"),
      PaymentMethodToken.from("tok_matrix_1").fold(error => fail(error.toString), identity),
      now
    )

  private def refundId(index: Int) =
    RefundId.from(UUID.fromString(f"00000000-0000-0000-0000-$index%012d"))

  private def op(value: String): ProviderOperationId =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)

  private def money(value: String): Money =
    Money.from(BigDecimal(value), Currency.PLN).fold(error => fail(error.toString), identity)

object PaymentTransitionMatrixSuite:
  val fixtureStateLabels: Set[String] =
    Set(
      "NotCreated",
      "Created",
      "FraudCheckPending",
      "FraudRejected",
      "ManualReview",
      "ReadyForAuthorization",
      "AuthorizationPending",
      "Authorized",
      "Declined",
      "AuthorizationUnknown",
      "CapturePending",
      "CaptureFailed",
      "CaptureUnknown",
      "Captured",
      "RefundPending",
      "PartiallyRefunded",
      "RefundFailed",
      "RefundUnknown",
      "Refunded"
    )

  val decisionCommandLabels: Set[String] =
    Set(
      "CreatePayment",
      "StartFraudCheck",
      "RecordFraudApproved",
      "RecordFraudRejected",
      "RecordFraudManualReview",
      "ApproveManualReview",
      "RejectManualReview",
      "AuthorizePayment",
      "RecordAuthorizationSucceeded",
      "RecordAuthorizationDeclined",
      "RecordAuthorizationUnknown",
      "ResolveAuthorizationUnknownAsSucceeded",
      "ResolveAuthorizationUnknownAsDeclined",
      "CapturePayment",
      "RecordCaptureSucceeded",
      "RecordCaptureFailed",
      "RecordCaptureUnknown",
      "ResolveCaptureUnknownAsSucceeded",
      "ResolveCaptureUnknownAsFailed",
      "RefundPayment",
      "RecordRefundSucceeded",
      "RecordRefundFailed",
      "RecordRefundUnknown",
      "ResolveRefundUnknownAsSucceeded",
      "ResolveRefundUnknownAsFailed"
    )

  val replayEventLabels: Set[String] =
    Set(
      "PaymentCreated",
      "FraudCheckRequested",
      "FraudCheckPassed",
      "FraudCheckRejected",
      "FraudManualReviewRequired",
      "FraudManualReviewApproved",
      "FraudManualReviewRejected",
      "AuthorizationRequested",
      "PaymentAuthorized",
      "PaymentDeclined",
      "AuthorizationOutcomeUnknown",
      "CaptureRequested",
      "PaymentCaptured",
      "CaptureFailed",
      "CaptureOutcomeUnknown",
      "RefundRequested",
      "PaymentPartiallyRefunded",
      "PaymentRefunded",
      "RefundFailed",
      "RefundOutcomeUnknown"
    )
