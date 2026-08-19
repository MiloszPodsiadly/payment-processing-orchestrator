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

final class PaymentHardeningSuite extends FunSuite:
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  test("single logical capture never emits a second capture request") {
    val pending = capturePendingState
    val unknown =
      PaymentDecider.evolve(
        pending,
        decideOne(pending, PaymentCommand.RecordCaptureUnknown(operation("capture-1"), now))
      )
    val captured = capturedState

    assertEquals(
      PaymentDecider.decide(captured, PaymentCommand.CapturePayment(operation("capture-2"), now)),
      Left(PaymentError.PaymentAlreadyCaptured)
    )
    assertEquals(
      PaymentDecider.decide(pending, PaymentCommand.CapturePayment(operation("capture-1"), now)),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider.decide(pending, PaymentCommand.CapturePayment(operation("capture-2"), now)),
      Left(PaymentError.OperationMismatch(operation("capture-1"), operation("capture-2")))
    )
    assertEquals(
      PaymentDecider.decide(unknown, PaymentCommand.CapturePayment(operation("capture-1"), now)),
      Left(PaymentError.OperationAlreadyInProgress(operation("capture-1")))
    )
    assertEquals(
      PaymentDecider
        .decide(captured, PaymentCommand.RecordCaptureSucceeded(operation("capture-1"), now)),
      Right(Nil)
    )
  }

  test("conflicting duplicate provider outcomes are rejected") {
    val authorized = authorizedState
    val captured = capturedState

    assertEquals(
      PaymentDecider.decide(
        authorized,
        PaymentCommand.RecordAuthorizationDeclined(operation("auth-1"), now)
      ),
      Left(PaymentError.ConflictingOperationOutcome(operation("auth-1")))
    )
    assertEquals(
      PaymentDecider
        .decide(captured, PaymentCommand.RecordCaptureFailed(operation("capture-1"), now)),
      Left(PaymentError.ConflictingOperationOutcome(operation("capture-1")))
    )
  }

  test("stale provider results cannot mutate current in-flight operations") {
    val authorizationPending = PaymentDecider.evolve(
      readyForAuthorization,
      decideOne(readyForAuthorization, PaymentCommand.AuthorizePayment(operation("auth-2"), now))
    )
    val capturePending = capturePendingState
    val refundPending =
      PaymentDecider.evolve(
        capturedState,
        decideOne(
          capturedState,
          PaymentCommand.RefundPayment(refundId(1), operation("refund-2"), money("10.00"), now)
        )
      )

    assertEquals(
      PaymentDecider.decide(
        authorizationPending,
        PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
      ),
      Left(PaymentError.OperationMismatch(operation("auth-2"), operation("auth-1")))
    )
    assertEquals(
      PaymentDecider.decide(
        capturePending,
        PaymentCommand.RecordCaptureSucceeded(operation("capture-old"), now)
      ),
      Left(PaymentError.OperationMismatch(operation("capture-1"), operation("capture-old")))
    )
    assertEquals(
      PaymentDecider
        .decide(refundPending, PaymentCommand.RecordRefundSucceeded(operation("refund-old"), now)),
      Left(PaymentError.OperationMismatch(operation("refund-2"), operation("refund-old")))
    )
  }

  test("evolve rejects provider operation identity mismatch in pending and unknown states") {
    assertEquals(
      PaymentDecider
        .evolve(
          authorizationPendingState,
          PaymentEvent.PaymentAuthorized(operation("auth-1"), now)
        )
        .productPrefix,
      "Authorized"
    )

    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        authorizationPendingState,
        PaymentEvent.PaymentAuthorized(operation("auth-wrong"), now)
      )
    }
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        authorizationPendingState,
        PaymentEvent.PaymentDeclined(operation("auth-wrong"), now)
      )
    }

    val authorizationUnknown =
      PaymentDecider.evolve(
        authorizationPendingState,
        PaymentEvent.AuthorizationOutcomeUnknown(operation("auth-1"), now)
      )

    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        authorizationUnknown,
        PaymentEvent.PaymentAuthorized(operation("auth-wrong"), now)
      )
    }

    assertEquals(
      PaymentDecider
        .evolve(capturePendingState, PaymentEvent.PaymentCaptured(operation("capture-1"), now))
        .productPrefix,
      "Captured"
    )

    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        capturePendingState,
        PaymentEvent.PaymentCaptured(operation("capture-wrong"), now)
      )
    }
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        capturePendingState,
        PaymentEvent.CaptureFailed(operation("capture-wrong"), now)
      )
    }
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        capturePendingState,
        PaymentEvent.CaptureOutcomeUnknown(operation("capture-wrong"), now)
      )
    }

    val captureUnknown =
      PaymentDecider.evolve(
        capturePendingState,
        PaymentEvent.CaptureOutcomeUnknown(operation("capture-1"), now)
      )

    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        captureUnknown,
        PaymentEvent.PaymentCaptured(operation("capture-wrong"), now)
      )
    }
    val _ = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        captureUnknown,
        PaymentEvent.CaptureFailed(operation("capture-wrong"), now)
      )
    }
  }

  test("unknown outcomes cannot be bypassed with fresh mutation commands") {
    val authorizationUnknown =
      PaymentDecider.evolve(
        authorizationPendingState,
        decideOne(
          authorizationPendingState,
          PaymentCommand.RecordAuthorizationUnknown(operation("auth-1"), now)
        )
      )
    val captureUnknown =
      PaymentDecider.evolve(
        capturePendingState,
        decideOne(
          capturePendingState,
          PaymentCommand.RecordCaptureUnknown(operation("capture-1"), now)
        )
      )
    val refundPending =
      PaymentDecider.evolve(
        capturedState,
        decideOne(
          capturedState,
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("10.00"), now)
        )
      )
    val refundUnknown =
      PaymentDecider.evolve(
        refundPending,
        decideOne(refundPending, PaymentCommand.RecordRefundUnknown(operation("refund-1"), now))
      )

    assertEquals(
      PaymentDecider
        .decide(authorizationUnknown, PaymentCommand.AuthorizePayment(operation("auth-2"), now)),
      Left(PaymentError.OperationMismatch(operation("auth-1"), operation("auth-2")))
    )
    assertEquals(
      PaymentDecider
        .decide(captureUnknown, PaymentCommand.CapturePayment(operation("capture-2"), now)),
      Left(PaymentError.OperationMismatch(operation("capture-1"), operation("capture-2")))
    )
    assertEquals(
      PaymentDecider.decide(
        refundUnknown,
        PaymentCommand.RefundPayment(refundId(2), operation("refund-2"), money("10.00"), now)
      ),
      Left(PaymentError.OperationMismatch(operation("refund-1"), operation("refund-2")))
    )
  }

  test("refund accounting rejects currency mismatch and over-refund by one minor unit") {
    val partiallyRefunded =
      applyRefund(capturedState, refundId(1), operation("refund-1"), money("90.00"))

    assertEquals(
      PaymentDecider.decide(
        partiallyRefunded,
        PaymentCommand.RefundPayment(refundId(2), operation("refund-2"), eur("1.00"), now)
      ),
      Left(PaymentError.RefundCurrencyMismatch)
    )
    assertEquals(
      PaymentDecider.decide(
        partiallyRefunded,
        PaymentCommand.RefundPayment(refundId(2), operation("refund-2"), money("10.01"), now)
      ),
      Left(PaymentError.RefundExceedsCapturedAmount)
    )
    assertEquals(
      PaymentDecider
        .evolve(
          PaymentDecider.evolve(
            partiallyRefunded,
            decideOne(
              partiallyRefunded,
              PaymentCommand.RefundPayment(refundId(2), operation("refund-2"), money("10.00"), now)
            )
          ),
          PaymentEvent.PaymentRefunded(refundId(2), operation("refund-2"), money("10.00"), now)
        )
        .productPrefix,
      "Refunded"
    )
  }

  test("duplicate refund commands and results are explicit") {
    val pending =
      PaymentDecider.evolve(
        capturedState,
        decideOne(
          capturedState,
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("10.00"), now)
        )
      )

    assertEquals(
      PaymentDecider.decide(
        pending,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("10.00"), now)
      ),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider.decide(
        pending,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("11.00"), now)
      ),
      Left(PaymentError.DuplicateRefundConflict(refundId(1)))
    )

    val partiallyRefunded =
      PaymentDecider.evolve(
        pending,
        decideOne(pending, PaymentCommand.RecordRefundSucceeded(operation("refund-1"), now))
      )

    assertEquals(
      PaymentDecider.decide(
        partiallyRefunded,
        PaymentCommand.RecordRefundSucceeded(operation("refund-1"), now)
      ),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider
        .decide(partiallyRefunded, PaymentCommand.RecordRefundFailed(operation("refund-1"), now)),
      Left(PaymentError.ConflictingOperationOutcome(operation("refund-1")))
    )
    assertEquals(
      PaymentDecider.decide(
        partiallyRefunded,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("10.00"), now)
      ),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider.decide(
        partiallyRefunded,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-2"), money("10.00"), now)
      ),
      Left(PaymentError.DuplicateRefundConflict(refundId(1)))
    )
    assertEquals(
      PaymentDecider.decide(
        partiallyRefunded,
        PaymentCommand.RefundPayment(refundId(2), operation("refund-1"), money("10.00"), now)
      ),
      Left(PaymentError.ProviderOperationAlreadyUsed(operation("refund-1")))
    )
  }

  test("provider operation IDs cannot be reused across logical mutation families") {
    assertEquals(
      PaymentDecider.decide(
        authorizedState,
        PaymentCommand.CapturePayment(operation("auth-1"), now)
      ),
      Left(PaymentError.ProviderOperationAlreadyUsed(operation("auth-1")))
    )
    assertEquals(
      PaymentDecider.decide(
        authorizedState,
        PaymentCommand.CapturePayment(operation("capture-unique"), now)
      ),
      Right(List(PaymentEvent.CaptureRequested(operation("capture-unique"), now)))
    )
    assertEquals(
      PaymentDecider.decide(
        capturedState,
        PaymentCommand.RefundPayment(refundId(1), operation("auth-1"), money("10.00"), now)
      ),
      Left(PaymentError.ProviderOperationAlreadyUsed(operation("auth-1")))
    )
    assertEquals(
      PaymentDecider.decide(
        capturedState,
        PaymentCommand.RefundPayment(refundId(1), operation("capture-1"), money("10.00"), now)
      ),
      Left(PaymentError.ProviderOperationAlreadyUsed(operation("capture-1")))
    )
    assertEquals(
      PaymentDecider.decide(
        capturedState,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-unique"), money("10.00"), now)
      ),
      Right(
        List(
          PaymentEvent.RefundRequested(refundId(1), operation("refund-unique"), money("10.00"), now)
        )
      )
    )
  }

  test("refund replay validates pending identity, amount, currency, bounds, and event kind") {
    val pending =
      PaymentDecider.evolve(
        capturedState,
        decideOne(
          capturedState,
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("10.00"), now)
        )
      )

    assertEquals(
      PaymentDecider
        .evolve(
          pending,
          PaymentEvent.PaymentPartiallyRefunded(
            refundId(1),
            operation("refund-1"),
            money("10.00"),
            now
          )
        )
        .productPrefix,
      "PartiallyRefunded"
    )

    List(
      PaymentEvent.PaymentPartiallyRefunded(
        refundId(2),
        operation("refund-1"),
        money("10.00"),
        now
      ),
      PaymentEvent.PaymentPartiallyRefunded(
        refundId(1),
        operation("refund-2"),
        money("10.00"),
        now
      ),
      PaymentEvent.PaymentPartiallyRefunded(
        refundId(1),
        operation("refund-1"),
        money("11.00"),
        now
      ),
      PaymentEvent.PaymentPartiallyRefunded(refundId(1), operation("refund-1"), eur("10.00"), now),
      PaymentEvent.PaymentRefunded(refundId(1), operation("refund-1"), money("10.00"), now)
    ).foreach { event =>
      intercept[InvalidPaymentHistory] {
        PaymentDecider.evolve(pending, event)
      }
    }

    val partiallyRefunded =
      applyRefund(capturedState, refundId(9), operation("refund-9"), money("90.00"))

    List(
      PaymentEvent.RefundRequested(refundId(10), operation("refund-10"), money("10.01"), now),
      PaymentEvent.RefundRequested(refundId(10), operation("refund-10"), eur("1.00"), now),
      PaymentEvent.RefundRequested(refundId(9), operation("refund-11"), money("1.00"), now),
      PaymentEvent.RefundRequested(refundId(11), operation("refund-9"), money("1.00"), now)
    ).foreach { event =>
      intercept[InvalidPaymentHistory] {
        PaymentDecider.evolve(partiallyRefunded, event)
      }
    }
  }

  test("full completed refund command replay is duplicate-safe") {
    val refunded = applyRefund(capturedState, refundId(1), operation("refund-1"), money("100.00"))

    assertEquals(
      PaymentDecider.decide(
        refunded,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("100.00"), now)
      ),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider.decide(
        refunded,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-2"), money("100.00"), now)
      ),
      Left(PaymentError.DuplicateRefundConflict(refundId(1)))
    )
    assertEquals(
      PaymentDecider.decide(
        refunded,
        PaymentCommand.RefundPayment(refundId(2), operation("refund-1"), money("1.00"), now)
      ),
      Left(PaymentError.ProviderOperationAlreadyUsed(operation("refund-1")))
    )
  }

  test("refund failed duplicate and conflicting result semantics are explicit") {
    val pending =
      PaymentDecider.evolve(
        capturedState,
        decideOne(
          capturedState,
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("10.00"), now)
        )
      )
    val failed =
      PaymentDecider.evolve(
        pending,
        decideOne(pending, PaymentCommand.RecordRefundFailed(operation("refund-1"), now))
      )

    assertEquals(
      PaymentDecider.decide(failed, PaymentCommand.RecordRefundFailed(operation("refund-1"), now)),
      Right(Nil)
    )
    assertEquals(
      PaymentDecider
        .decide(failed, PaymentCommand.RecordRefundSucceeded(operation("refund-1"), now)),
      Left(PaymentError.ConflictingOperationOutcome(operation("refund-1")))
    )
    assertEquals(
      PaymentDecider.decide(failed, PaymentCommand.RecordRefundFailed(operation("refund-2"), now)),
      Left(PaymentError.OperationMismatch(operation("refund-1"), operation("refund-2")))
    )
  }

  test("domain protocol diagnostics redact payment method token") {
    val rawToken = "tok_NO_MERCY_DO_NOT_LOG_123"
    val token = PaymentMethodToken.from(rawToken).fold(error => fail(error.toString), identity)
    val sensitivePayment =
      Payment(
        PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000501")),
        TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000502")),
        CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000503")),
        MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000504")),
        money("100.00"),
        token,
        now
      )
    val createCommand: PaymentCommand.CreatePayment =
      PaymentCommand.CreatePayment(
        sensitivePayment.paymentId,
        sensitivePayment.tenantId,
        sensitivePayment.customerId,
        sensitivePayment.merchantId,
        sensitivePayment.amount,
        token,
        now
      )
    val createdEvent: PaymentEvent.PaymentCreated =
      PaymentEvent.PaymentCreated(
        sensitivePayment.paymentId,
        sensitivePayment.tenantId,
        sensitivePayment.customerId,
        sensitivePayment.merchantId,
        sensitivePayment.amount,
        token,
        now
      )

    assert(!sensitivePayment.toString.contains(rawToken))
    assert(sensitivePayment.toString.contains("[REDACTED]"))
    assert(!createCommand.toString.contains(rawToken))
    assert(!createdEvent.toString.contains(rawToken))
    assertEquals(createCommand.paymentMethodToken.value, rawToken)
    assertEquals(createdEvent.paymentMethodToken.value, rawToken)

    val exception = intercept[InvalidPaymentHistory] {
      PaymentDecider.evolve(
        PaymentState.Created(sensitivePayment),
        PaymentEvent.PaymentCaptured(operation("capture-1"), now)
      )
    }

    assert(!exception.getMessage.contains(rawToken))
    assert(!exception.toString.contains(rawToken))
    assert(exception.getMessage.contains("Created"))
    assert(exception.getMessage.contains("PaymentCaptured"))
  }

  test("payment core data is unchanged across legal transitions") {
    val states = List(
      readyForAuthorization,
      authorizationPendingState,
      authorizedState,
      capturePendingState,
      capturedState,
      applyRefund(capturedState, refundId(1), operation("refund-1"), money("10.00"))
    )

    assert(states.forall(extractPayment(_) == payment))
  }

  private def applyRefund(
      state: PaymentState,
      refundId: RefundId,
      operationId: ProviderOperationId,
      amount: Money
  ): PaymentState =
    val pending =
      PaymentDecider.evolve(
        state,
        decideOne(state, PaymentCommand.RefundPayment(refundId, operationId, amount, now))
      )

    PaymentDecider.evolve(
      pending,
      decideOne(pending, PaymentCommand.RecordRefundSucceeded(operationId, now))
    )

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

  private def authorizationPendingState =
    PaymentDecider.evolve(
      readyForAuthorization,
      decideOne(readyForAuthorization, PaymentCommand.AuthorizePayment(operation("auth-1"), now))
    )

  private def authorizedState =
    PaymentDecider.evolve(
      authorizationPendingState,
      decideOne(
        authorizationPendingState,
        PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
      )
    )

  private def capturePendingState =
    PaymentDecider.evolve(
      authorizedState,
      decideOne(authorizedState, PaymentCommand.CapturePayment(operation("capture-1"), now))
    )

  private def capturedState =
    PaymentDecider.evolve(
      capturePendingState,
      decideOne(
        capturePendingState,
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
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000301")),
      TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000302")),
      CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000303")),
      MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000304")),
      money("100.00"),
      PaymentMethodToken.from("tok_hardening_1").fold(error => fail(error.toString), identity),
      now
    )

  private def refundId(index: Int) =
    RefundId.from(UUID.fromString(f"00000000-0000-0000-0000-$index%012d"))

  private def operation(value: String) =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)

  private def money(value: String) =
    Money.from(BigDecimal(value), Currency.PLN).fold(error => fail(error.toString), identity)

  private def eur(value: String) =
    Money.from(BigDecimal(value), Currency.EUR).fold(error => fail(error.toString), identity)
