package com.paymentprocessing.domain.payment

import com.paymentprocessing.domain.identity.ProviderOperationId

object PaymentDecider:
  def decide(
      state: PaymentState,
      command: PaymentCommand
  ): Either[PaymentError, List[PaymentEvent]] =
    (state, command) match
      case (PaymentState.NotCreated, command: PaymentCommand.CreatePayment) =>
        Right(List(created(command)))

      case (state, _: PaymentCommand.CreatePayment) if isCreated(state) =>
        Left(PaymentError.PaymentAlreadyCreated)

      case (PaymentState.Created(_), PaymentCommand.StartFraudCheck(occurredAt)) =>
        Right(List(PaymentEvent.FraudCheckRequested(occurredAt)))

      case (PaymentState.FraudCheckPending(_), PaymentCommand.RecordFraudApproved(occurredAt)) =>
        Right(List(PaymentEvent.FraudCheckPassed(occurredAt)))

      case (PaymentState.FraudCheckPending(_), PaymentCommand.RecordFraudRejected(occurredAt)) =>
        Right(List(PaymentEvent.FraudCheckRejected(occurredAt)))

      case (
            PaymentState.FraudCheckPending(_),
            PaymentCommand.RecordFraudManualReview(occurredAt)
          ) =>
        Right(List(PaymentEvent.FraudManualReviewRequired(occurredAt)))

      case (PaymentState.ManualReview(_), PaymentCommand.ApproveManualReview(occurredAt)) =>
        Right(List(PaymentEvent.FraudManualReviewApproved(occurredAt)))

      case (PaymentState.ManualReview(_), PaymentCommand.RejectManualReview(occurredAt)) =>
        Right(List(PaymentEvent.FraudManualReviewRejected(occurredAt)))

      case (PaymentState.FraudRejected(_), _: PaymentCommand.AuthorizePayment) =>
        Left(PaymentError.FraudRejected)

      case (
            PaymentState.ReadyForAuthorization(_),
            PaymentCommand.AuthorizePayment(operationId, occurredAt)
          ) =>
        Right(List(PaymentEvent.AuthorizationRequested(operationId, occurredAt)))

      case (PaymentState.AuthorizationPending(_, expected), AuthorizationResultCommand(result)) =>
        matchOperation(expected, result.operationId).map(_ => List(result.toEvent))

      case (
            PaymentState.AuthorizationUnknown(_, expected),
            AuthorizationResolutionCommand(result)
          ) =>
        matchOperation(expected, result.operationId).map(_ => List(result.toEvent))

      case (
            PaymentState.AuthorizationPending(_, expected),
            PaymentCommand.AuthorizePayment(operationId, _)
          ) =>
        noOpIfSameOperation(expected, operationId)

      case (
            PaymentState.AuthorizationUnknown(_, expected),
            PaymentCommand.AuthorizePayment(operationId, _)
          ) =>
        Left(inProgressOrMismatch(expected, operationId))

      case (
            PaymentState.Authorized(_, authorization),
            PaymentCommand.RecordAuthorizationSucceeded(operationId, _)
          ) =>
        noOpIfSameOperation(authorization.operationId, operationId)

      case (PaymentState.Authorized(_, authorization), AuthorizationResultInput(result)) =>
        conflictingOrMismatch(authorization.operationId, result.operationId)

      case (
            PaymentState.Declined(_, authorization),
            PaymentCommand.RecordAuthorizationDeclined(operationId, _)
          ) =>
        noOpIfSameOperation(authorization.operationId, operationId)

      case (PaymentState.Declined(_, authorization), AuthorizationResultInput(result)) =>
        conflictingOrMismatch(authorization.operationId, result.operationId)

      case (PaymentState.Declined(_, _), _: PaymentCommand.CapturePayment) =>
        Left(PaymentError.PaymentDeclined)

      case (
            PaymentState.Authorized(_, _),
            PaymentCommand.CapturePayment(operationId, occurredAt)
          ) =>
        Right(List(PaymentEvent.CaptureRequested(operationId, occurredAt)))

      case (PaymentState.CapturePending(_, _, expected), CaptureResultCommand(result)) =>
        matchOperation(expected, result.operationId).map(_ => List(result.toEvent))

      case (PaymentState.CaptureUnknown(_, _, expected), CaptureResolutionCommand(result)) =>
        matchOperation(expected, result.operationId).map(_ => List(result.toEvent))

      case (
            PaymentState.CapturePending(_, _, expected),
            PaymentCommand.CapturePayment(operationId, _)
          ) =>
        noOpIfSameOperation(expected, operationId)

      case (
            PaymentState.CaptureUnknown(_, _, expected),
            PaymentCommand.CapturePayment(operationId, _)
          ) =>
        Left(inProgressOrMismatch(expected, operationId))

      case (PaymentState.Captured(_, _, _, _), _: PaymentCommand.CapturePayment) =>
        Left(PaymentError.PaymentAlreadyCaptured)

      case (PaymentState.PartiallyRefunded(_, _, _, _), _: PaymentCommand.CapturePayment) =>
        Left(PaymentError.PaymentAlreadyCaptured)

      case (PaymentState.Refunded(_, _, _, _), _: PaymentCommand.CapturePayment) =>
        Left(PaymentError.PaymentAlreadyCaptured)

      case (
            PaymentState.Captured(_, _, capture, _),
            PaymentCommand.RecordCaptureSucceeded(operationId, _)
          ) =>
        noOpIfSameOperation(capture.operationId, operationId)

      case (PaymentState.Captured(_, _, capture, _), CaptureResultInput(result)) =>
        conflictingOrMismatch(capture.operationId, result.operationId)

      case (
            PaymentState.CaptureFailed(_, _, expected),
            PaymentCommand.RecordCaptureFailed(operationId, _)
          ) =>
        noOpIfSameOperation(expected, operationId)

      case (PaymentState.CaptureFailed(_, _, expected), CaptureResultInput(result)) =>
        conflictingOrMismatch(expected, result.operationId)

      case (state @ PaymentState.Captured(_, _, _, _), command: PaymentCommand.RefundPayment) =>
        decideRefund(state, command)

      case (
            state @ PaymentState.PartiallyRefunded(_, _, _, _),
            command: PaymentCommand.RefundPayment
          ) =>
        decideRefund(state, command)

      case (PaymentState.Authorized(_, _), _: PaymentCommand.RefundPayment) =>
        Left(PaymentError.PaymentNotCaptured)

      case (state @ PaymentState.Refunded(_, _, _, _), command: PaymentCommand.RefundPayment) =>
        completedRefundReplay(state, command, PaymentError.PaymentAlreadyRefunded)

      case (
            PaymentState.RefundPending(_, _, _, _, pending),
            command: PaymentCommand.RefundPayment
          ) =>
        duplicateRefundCommand(pending, command)

      case (
            PaymentState.RefundUnknown(_, _, _, _, pending),
            command: PaymentCommand.RefundPayment
          ) =>
        duplicateRefundCommand(pending, command)

      case (
            PaymentState.RefundPending(_, _, capture, completedRefunds, pending),
            PaymentCommand.RecordRefundSucceeded(operationId, occurredAt)
          ) =>
        matchOperation(pending.operationId, operationId)
          .map(_ => List(successfulRefundEvent(capture, completedRefunds, pending, occurredAt)))

      case (
            PaymentState.RefundPending(_, _, _, _, pending),
            PaymentCommand.RecordRefundFailed(operationId, occurredAt)
          ) =>
        matchOperation(pending.operationId, operationId)
          .map(_ => List(PaymentEvent.RefundFailed(pending.operationId, occurredAt)))

      case (
            PaymentState.RefundPending(_, _, _, _, pending),
            PaymentCommand.RecordRefundUnknown(operationId, occurredAt)
          ) =>
        matchOperation(pending.operationId, operationId)
          .map(_ => List(PaymentEvent.RefundOutcomeUnknown(pending.operationId, occurredAt)))

      case (
            PaymentState.RefundUnknown(_, _, capture, completedRefunds, pending),
            PaymentCommand.ResolveRefundUnknownAsSucceeded(operationId, occurredAt)
          ) =>
        matchOperation(pending.operationId, operationId)
          .map(_ => List(successfulRefundEvent(capture, completedRefunds, pending, occurredAt)))

      case (
            PaymentState.RefundUnknown(_, _, _, _, pending),
            PaymentCommand.ResolveRefundUnknownAsFailed(operationId, occurredAt)
          ) =>
        matchOperation(pending.operationId, operationId)
          .map(_ => List(PaymentEvent.RefundFailed(pending.operationId, occurredAt)))

      case (PaymentState.PartiallyRefunded(_, _, _, refunds), RefundResultInput(result)) =>
        duplicateCompletedRefundResult(refunds, result)

      case (PaymentState.Refunded(_, _, _, refunds), RefundResultInput(result)) =>
        duplicateCompletedRefundResult(refunds, result)

      case (
            PaymentState.RefundFailed(_, _, _, _, failedRefund),
            PaymentCommand.RecordRefundFailed(operationId, _)
          ) =>
        noOpIfSameOperation(failedRefund.operationId, operationId)

      case (PaymentState.RefundFailed(_, _, _, _, failedRefund), RefundResultInput(result)) =>
        conflictingOrMismatch(failedRefund.operationId, result.operationId)

      case (PaymentState.NotCreated, _) =>
        Left(PaymentError.PaymentNotCreated)

      case _ =>
        Left(PaymentError.InvalidStateTransition)

  def evolve(state: PaymentState, event: PaymentEvent): PaymentState =
    (state, event) match
      case (PaymentState.NotCreated, event: PaymentEvent.PaymentCreated) =>
        PaymentState.Created(payment(event))

      case (PaymentState.Created(payment), PaymentEvent.FraudCheckRequested(_)) =>
        PaymentState.FraudCheckPending(payment)

      case (PaymentState.FraudCheckPending(payment), PaymentEvent.FraudCheckPassed(_)) =>
        PaymentState.ReadyForAuthorization(payment)

      case (PaymentState.FraudCheckPending(payment), PaymentEvent.FraudCheckRejected(_)) =>
        PaymentState.FraudRejected(payment)

      case (PaymentState.FraudCheckPending(payment), PaymentEvent.FraudManualReviewRequired(_)) =>
        PaymentState.ManualReview(payment)

      case (PaymentState.ManualReview(payment), PaymentEvent.FraudManualReviewApproved(_)) =>
        PaymentState.ReadyForAuthorization(payment)

      case (PaymentState.ManualReview(payment), PaymentEvent.FraudManualReviewRejected(_)) =>
        PaymentState.FraudRejected(payment)

      case (
            PaymentState.ReadyForAuthorization(payment),
            PaymentEvent.AuthorizationRequested(operationId, _)
          ) =>
        PaymentState.AuthorizationPending(payment, operationId)

      case (
            PaymentState.AuthorizationPending(payment, expected),
            PaymentEvent.PaymentAuthorized(operationId, occurredAt)
          ) =>
        requireOperation(state, event, expected, operationId, "authorization success")
        PaymentState.Authorized(payment, AuthorizationRecord(operationId, occurredAt))

      case (
            PaymentState.AuthorizationUnknown(payment, expected),
            PaymentEvent.PaymentAuthorized(operationId, occurredAt)
          ) =>
        requireOperation(state, event, expected, operationId, "authorization success")
        PaymentState.Authorized(payment, AuthorizationRecord(operationId, occurredAt))

      case (
            PaymentState.AuthorizationPending(payment, expected),
            PaymentEvent.PaymentDeclined(operationId, occurredAt)
          ) =>
        requireOperation(state, event, expected, operationId, "authorization decline")
        PaymentState.Declined(payment, AuthorizationRecord(operationId, occurredAt))

      case (
            PaymentState.AuthorizationUnknown(payment, expected),
            PaymentEvent.PaymentDeclined(operationId, occurredAt)
          ) =>
        requireOperation(state, event, expected, operationId, "authorization decline")
        PaymentState.Declined(payment, AuthorizationRecord(operationId, occurredAt))

      case (
            PaymentState.AuthorizationPending(payment, expected),
            PaymentEvent.AuthorizationOutcomeUnknown(operationId, _)
          ) =>
        requireOperation(state, event, expected, operationId, "authorization unknown")
        PaymentState.AuthorizationUnknown(payment, operationId)

      case (
            PaymentState.Authorized(payment, authorization),
            PaymentEvent.CaptureRequested(operationId, _)
          ) =>
        PaymentState.CapturePending(payment, authorization, operationId)

      case (
            PaymentState.CapturePending(payment, authorization, expected),
            PaymentEvent.PaymentCaptured(operationId, occurredAt)
          ) =>
        requireOperation(state, event, expected, operationId, "capture success")
        PaymentState.Captured(
          payment,
          authorization,
          CaptureRecord(operationId, payment.amount, occurredAt),
          refunds = Nil
        )

      case (
            PaymentState.CaptureUnknown(payment, authorization, expected),
            PaymentEvent.PaymentCaptured(operationId, occurredAt)
          ) =>
        requireOperation(state, event, expected, operationId, "capture success")
        PaymentState.Captured(
          payment,
          authorization,
          CaptureRecord(operationId, payment.amount, occurredAt),
          refunds = Nil
        )

      case (
            PaymentState.CapturePending(payment, authorization, expected),
            PaymentEvent.CaptureFailed(operationId, _)
          ) =>
        requireOperation(state, event, expected, operationId, "capture failure")
        PaymentState.CaptureFailed(payment, authorization, operationId)

      case (
            PaymentState.CaptureUnknown(payment, authorization, expected),
            PaymentEvent.CaptureFailed(operationId, _)
          ) =>
        requireOperation(state, event, expected, operationId, "capture failure")
        PaymentState.CaptureFailed(payment, authorization, operationId)

      case (
            PaymentState.CapturePending(payment, authorization, expected),
            PaymentEvent.CaptureOutcomeUnknown(operationId, _)
          ) =>
        requireOperation(state, event, expected, operationId, "capture unknown")
        PaymentState.CaptureUnknown(payment, authorization, operationId)

      case (state: RefundableState, event: PaymentEvent.RefundRequested) =>
        val data = refundableData(state)
        validateRefundRequestHistory(state, event, data)
        PaymentState.RefundPending(
          data.payment,
          data.authorization,
          data.capture,
          data.refunds,
          PendingRefund(event.refundId, event.operationId, event.amount, event.occurredAt)
        )

      case (
            PaymentState.RefundPending(payment, authorization, capture, completedRefunds, pending),
            event: PaymentEvent.PaymentPartiallyRefunded
          ) =>
        validateRefundSuccessHistory(state, event, capture, completedRefunds, pending)
        PaymentState.PartiallyRefunded(
          payment,
          authorization,
          capture,
          completedRefunds :+ RefundRecord(
            event.refundId,
            event.operationId,
            event.amount,
            event.occurredAt
          )
        )

      case (
            PaymentState.RefundUnknown(payment, authorization, capture, completedRefunds, pending),
            event: PaymentEvent.PaymentPartiallyRefunded
          ) =>
        validateRefundSuccessHistory(state, event, capture, completedRefunds, pending)
        PaymentState.PartiallyRefunded(
          payment,
          authorization,
          capture,
          completedRefunds :+ RefundRecord(
            event.refundId,
            event.operationId,
            event.amount,
            event.occurredAt
          )
        )

      case (
            PaymentState.RefundPending(payment, authorization, capture, completedRefunds, pending),
            event: PaymentEvent.PaymentRefunded
          ) =>
        validateRefundSuccessHistory(state, event, capture, completedRefunds, pending)
        PaymentState.Refunded(
          payment,
          authorization,
          capture,
          completedRefunds :+ RefundRecord(
            event.refundId,
            event.operationId,
            event.amount,
            event.occurredAt
          )
        )

      case (
            PaymentState.RefundUnknown(payment, authorization, capture, completedRefunds, pending),
            event: PaymentEvent.PaymentRefunded
          ) =>
        validateRefundSuccessHistory(state, event, capture, completedRefunds, pending)
        PaymentState.Refunded(
          payment,
          authorization,
          capture,
          completedRefunds :+ RefundRecord(
            event.refundId,
            event.operationId,
            event.amount,
            event.occurredAt
          )
        )

      case (
            PaymentState.RefundPending(payment, authorization, capture, completedRefunds, pending),
            PaymentEvent.RefundFailed(operationId, _)
          ) =>
        requireOperation(state, event, pending.operationId, operationId, "refund failure")
        PaymentState.RefundFailed(payment, authorization, capture, completedRefunds, pending)

      case (
            PaymentState.RefundUnknown(payment, authorization, capture, completedRefunds, pending),
            PaymentEvent.RefundFailed(operationId, _)
          ) =>
        requireOperation(state, event, pending.operationId, operationId, "refund failure")
        PaymentState.RefundFailed(payment, authorization, capture, completedRefunds, pending)

      case (
            PaymentState.RefundPending(payment, authorization, capture, completedRefunds, pending),
            PaymentEvent.RefundOutcomeUnknown(operationId, _)
          ) =>
        requireOperation(state, event, pending.operationId, operationId, "refund unknown")
        PaymentState.RefundUnknown(payment, authorization, capture, completedRefunds, pending)

      case _ =>
        invalidHistory(state, event, "event is not legal for current state")

  private def created(command: PaymentCommand.CreatePayment): PaymentEvent =
    PaymentEvent.PaymentCreated(
      command.paymentId,
      command.tenantId,
      command.customerId,
      command.merchantId,
      command.amount,
      command.paymentMethodToken,
      command.occurredAt
    )

  private def payment(event: PaymentEvent.PaymentCreated): Payment =
    Payment(
      event.paymentId,
      event.tenantId,
      event.customerId,
      event.merchantId,
      event.amount,
      event.paymentMethodToken,
      event.occurredAt
    )

  private def isCreated(state: PaymentState): Boolean =
    state != PaymentState.NotCreated

  private def matchOperation(
      expected: ProviderOperationId,
      actual: ProviderOperationId
  ): Either[PaymentError, Unit] =
    Either.cond(expected == actual, (), PaymentError.OperationMismatch(expected, actual))

  private def noOpIfSameOperation(
      expected: ProviderOperationId,
      actual: ProviderOperationId
  ): Either[PaymentError, List[PaymentEvent]] =
    if expected == actual then Right(Nil)
    else Left(PaymentError.OperationMismatch(expected, actual))

  private def conflictingOrMismatch(
      expected: ProviderOperationId,
      actual: ProviderOperationId
  ): Either[PaymentError, List[PaymentEvent]] =
    if expected == actual then Left(PaymentError.ConflictingOperationOutcome(expected))
    else Left(PaymentError.OperationMismatch(expected, actual))

  private def inProgressOrMismatch(
      expected: ProviderOperationId,
      actual: ProviderOperationId
  ): PaymentError =
    if expected == actual then PaymentError.OperationAlreadyInProgress(expected)
    else PaymentError.OperationMismatch(expected, actual)

  private type RefundableState = PaymentState.Captured | PaymentState.PartiallyRefunded

  private final case class RefundableData(
      payment: Payment,
      authorization: AuthorizationRecord,
      capture: CaptureRecord,
      refunds: List[RefundRecord]
  )

  private def refundableData(state: RefundableState): RefundableData =
    state match
      case PaymentState.Captured(payment, authorization, capture, refunds) =>
        RefundableData(payment, authorization, capture, refunds)
      case PaymentState.PartiallyRefunded(payment, authorization, capture, refunds) =>
        RefundableData(payment, authorization, capture, refunds)

  private def decideRefund(
      state: RefundableState,
      command: PaymentCommand.RefundPayment
  ): Either[PaymentError, List[PaymentEvent]] =
    val data = refundableData(state)

    val completedReplay = completedRefundReplay(data.refunds, command)
    if completedReplay.isDefined then completedReplay.get
    else if data.refunds.exists(_.operationId == command.operationId) then
      Left(PaymentError.ProviderOperationAlreadyUsed(command.operationId))
    else if command.amount.currency != data.capture.amount.currency then
      Left(PaymentError.RefundCurrencyMismatch)
    else
      val totalAfterRefund = totalRefunded(data.refunds) + command.amount.amount
      val capturedAmount = data.capture.amount.amount

      if totalAfterRefund > capturedAmount then Left(PaymentError.RefundExceedsCapturedAmount)
      else
        Right(
          List(
            PaymentEvent.RefundRequested(
              command.refundId,
              command.operationId,
              command.amount,
              command.occurredAt
            )
          )
        )

  private def completedRefundReplay(
      state: PaymentState,
      command: PaymentCommand.RefundPayment,
      fallback: PaymentError
  ): Either[PaymentError, List[PaymentEvent]] =
    state match
      case PaymentState.Refunded(_, _, _, refunds) =>
        completedRefundReplay(refunds, command).getOrElse(Left(fallback))
      case _ => Left(fallback)

  private def completedRefundReplay(
      refunds: List[RefundRecord],
      command: PaymentCommand.RefundPayment
  ): Option[Either[PaymentError, List[PaymentEvent]]] =
    refunds.find(_.refundId == command.refundId) match
      case Some(refund)
          if refund.operationId == command.operationId && refund.amount == command.amount =>
        Some(Right(Nil))
      case Some(_) =>
        Some(Left(PaymentError.DuplicateRefundConflict(command.refundId)))
      case None if refunds.exists(_.operationId == command.operationId) =>
        Some(Left(PaymentError.ProviderOperationAlreadyUsed(command.operationId)))
      case None => None

  private def totalRefunded(refunds: List[RefundRecord]): BigDecimal =
    refunds.map(_.amount.amount).foldLeft(BigDecimal(0))(_ + _)

  private def duplicateRefundCommand(
      pending: PendingRefund,
      command: PaymentCommand.RefundPayment
  ): Either[PaymentError, List[PaymentEvent]] =
    if pending.refundId == command.refundId && pending.operationId == command.operationId && pending.amount == command.amount
    then Right(Nil)
    else if pending.refundId == command.refundId then
      Left(PaymentError.DuplicateRefundConflict(command.refundId))
    else Left(inProgressOrMismatch(pending.operationId, command.operationId))

  private def duplicateCompletedRefundResult(
      refunds: List[RefundRecord],
      result: RefundResultInput
  ): Either[PaymentError, List[PaymentEvent]] =
    refunds.find(_.operationId == result.operationId) match
      case Some(_) if result.isSuccess => Right(Nil)
      case Some(_) => Left(PaymentError.ConflictingOperationOutcome(result.operationId))
      case None =>
        Left(PaymentError.OperationMismatch(refunds.last.operationId, result.operationId))

  private trait AuthorizationResultInput:
    def operationId: ProviderOperationId

  private object AuthorizationResultInput:
    def unapply(command: PaymentCommand): Option[AuthorizationResultInput] =
      command match
        case PaymentCommand.RecordAuthorizationSucceeded(op, _) =>
          Some(new AuthorizationResultInput:
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordAuthorizationDeclined(op, _) =>
          Some(new AuthorizationResultInput:
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordAuthorizationUnknown(op, _) =>
          Some(new AuthorizationResultInput:
            val operationId: ProviderOperationId = op)
        case _ => None

  private trait CaptureResultInput:
    def operationId: ProviderOperationId

  private object CaptureResultInput:
    def unapply(command: PaymentCommand): Option[CaptureResultInput] =
      command match
        case PaymentCommand.RecordCaptureSucceeded(op, _) =>
          Some(new CaptureResultInput:
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordCaptureFailed(op, _) =>
          Some(new CaptureResultInput:
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordCaptureUnknown(op, _) =>
          Some(new CaptureResultInput:
            val operationId: ProviderOperationId = op)
        case _ => None

  private trait RefundResultInput:
    def operationId: ProviderOperationId
    def isSuccess: Boolean

  private object RefundResultInput:
    def unapply(command: PaymentCommand): Option[RefundResultInput] =
      command match
        case PaymentCommand.RecordRefundSucceeded(op, _) =>
          Some(new RefundResultInput:
            val operationId: ProviderOperationId = op
            val isSuccess: Boolean = true)
        case PaymentCommand.RecordRefundFailed(op, _) =>
          Some(new RefundResultInput:
            val operationId: ProviderOperationId = op
            val isSuccess: Boolean = false)
        case PaymentCommand.RecordRefundUnknown(op, _) =>
          Some(new RefundResultInput:
            val operationId: ProviderOperationId = op
            val isSuccess: Boolean = false)
        case PaymentCommand.ResolveRefundUnknownAsSucceeded(op, _) =>
          Some(new RefundResultInput:
            val operationId: ProviderOperationId = op
            val isSuccess: Boolean = true)
        case PaymentCommand.ResolveRefundUnknownAsFailed(op, _) =>
          Some(new RefundResultInput:
            val operationId: ProviderOperationId = op
            val isSuccess: Boolean = false)
        case _ => None

  private trait AuthorizationResultCommand:
    def operationId: ProviderOperationId
    def toEvent: PaymentEvent

  private object AuthorizationResultCommand:
    def unapply(command: PaymentCommand): Option[AuthorizationResultCommand] =
      command match
        case PaymentCommand.RecordAuthorizationSucceeded(op, occurredAt) =>
          Some(new AuthorizationResultCommand:
            val toEvent: PaymentEvent = PaymentEvent.PaymentAuthorized(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordAuthorizationDeclined(op, occurredAt) =>
          Some(new AuthorizationResultCommand:
            val toEvent: PaymentEvent = PaymentEvent.PaymentDeclined(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordAuthorizationUnknown(op, occurredAt) =>
          Some(new AuthorizationResultCommand:
            val toEvent: PaymentEvent = PaymentEvent.AuthorizationOutcomeUnknown(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case _ => None

  private trait AuthorizationResolutionCommand:
    def operationId: ProviderOperationId
    def toEvent: PaymentEvent

  private object AuthorizationResolutionCommand:
    def unapply(command: PaymentCommand): Option[AuthorizationResolutionCommand] =
      command match
        case PaymentCommand.ResolveAuthorizationUnknownAsSucceeded(op, occurredAt) =>
          Some(new AuthorizationResolutionCommand:
            val toEvent: PaymentEvent = PaymentEvent.PaymentAuthorized(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case PaymentCommand.ResolveAuthorizationUnknownAsDeclined(op, occurredAt) =>
          Some(new AuthorizationResolutionCommand:
            val toEvent: PaymentEvent = PaymentEvent.PaymentDeclined(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case _ => None

  private trait CaptureResultCommand:
    def operationId: ProviderOperationId
    def toEvent: PaymentEvent

  private object CaptureResultCommand:
    def unapply(command: PaymentCommand): Option[CaptureResultCommand] =
      command match
        case PaymentCommand.RecordCaptureSucceeded(op, occurredAt) =>
          Some(new CaptureResultCommand:
            val toEvent: PaymentEvent = PaymentEvent.PaymentCaptured(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordCaptureFailed(op, occurredAt) =>
          Some(new CaptureResultCommand:
            val toEvent: PaymentEvent = PaymentEvent.CaptureFailed(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case PaymentCommand.RecordCaptureUnknown(op, occurredAt) =>
          Some(new CaptureResultCommand:
            val toEvent: PaymentEvent = PaymentEvent.CaptureOutcomeUnknown(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case _ => None

  private trait CaptureResolutionCommand:
    def operationId: ProviderOperationId
    def toEvent: PaymentEvent

  private object CaptureResolutionCommand:
    def unapply(command: PaymentCommand): Option[CaptureResolutionCommand] =
      command match
        case PaymentCommand.ResolveCaptureUnknownAsSucceeded(op, occurredAt) =>
          Some(new CaptureResolutionCommand:
            val toEvent: PaymentEvent = PaymentEvent.PaymentCaptured(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case PaymentCommand.ResolveCaptureUnknownAsFailed(op, occurredAt) =>
          Some(new CaptureResolutionCommand:
            val toEvent: PaymentEvent = PaymentEvent.CaptureFailed(op, occurredAt)
            val operationId: ProviderOperationId = op)
        case _ => None

  private def successfulRefundEvent(
      capture: CaptureRecord,
      completedRefunds: List[RefundRecord],
      pendingRefund: PendingRefund,
      occurredAt: java.time.Instant
  ): PaymentEvent =
    val totalAfterRefund = totalRefunded(completedRefunds) + pendingRefund.amount.amount

    if totalAfterRefund == capture.amount.amount then
      PaymentEvent.PaymentRefunded(
        pendingRefund.refundId,
        pendingRefund.operationId,
        pendingRefund.amount,
        occurredAt
      )
    else
      PaymentEvent.PaymentPartiallyRefunded(
        pendingRefund.refundId,
        pendingRefund.operationId,
        pendingRefund.amount,
        occurredAt
      )

  private def requireOperation(
      state: PaymentState,
      event: PaymentEvent,
      expected: ProviderOperationId,
      actual: ProviderOperationId,
      context: String
  ): Unit =
    if expected != actual then
      invalidHistory(
        state,
        event,
        s"$context operation mismatch: expected=${expected.value}, actual=${actual.value}"
      )

  private def validateRefundRequestHistory(
      state: PaymentState,
      event: PaymentEvent.RefundRequested,
      data: RefundableData
  ): Unit =
    if event.amount.currency != data.capture.amount.currency then
      invalidHistory(state, event, "refund request currency does not match capture currency")
    if data.refunds.exists(_.refundId == event.refundId) then
      invalidHistory(state, event, "refund request reuses completed refund ID")
    if data.refunds.exists(_.operationId == event.operationId) then
      invalidHistory(state, event, "refund request reuses completed provider operation ID")

    val totalAfterRefund = totalRefunded(data.refunds) + event.amount.amount
    if totalAfterRefund > data.capture.amount.amount then
      invalidHistory(state, event, "refund request exceeds captured amount")

  private def validateRefundSuccessHistory(
      state: PaymentState,
      event: PaymentEvent.PaymentPartiallyRefunded | PaymentEvent.PaymentRefunded,
      capture: CaptureRecord,
      completedRefunds: List[RefundRecord],
      pending: PendingRefund
  ): Unit =
    val (refundId, operationId, amount, isFullRefundEvent) =
      event match
        case PaymentEvent.PaymentPartiallyRefunded(refundId, operationId, amount, _) =>
          (refundId, operationId, amount, false)
        case PaymentEvent.PaymentRefunded(refundId, operationId, amount, _) =>
          (refundId, operationId, amount, true)

    if refundId != pending.refundId then
      invalidHistory(state, event, "refund success ID does not match pending refund")
    if operationId != pending.operationId then
      invalidHistory(state, event, "refund success operation does not match pending refund")
    if amount != pending.amount then
      invalidHistory(state, event, "refund success amount does not match pending refund")
    if amount.currency != capture.amount.currency then
      invalidHistory(state, event, "refund success currency does not match capture currency")

    val totalAfterRefund = totalRefunded(completedRefunds) + amount.amount
    if totalAfterRefund > capture.amount.amount then
      invalidHistory(state, event, "refund success exceeds captured amount")

    val shouldBeFullRefund = totalAfterRefund == capture.amount.amount
    if shouldBeFullRefund != isFullRefundEvent then
      invalidHistory(state, event, "refund success event kind does not match financial result")

  private def invalidHistory(
      state: PaymentState,
      event: PaymentEvent,
      reason: String
  ): Nothing =
    throw InvalidPaymentHistory(state, event, reason)

final case class InvalidPaymentHistory(
    state: PaymentState,
    event: PaymentEvent,
    reason: String = "event is not legal for current state"
) extends IllegalStateException(
      s"Invalid payment event history: state=${InvalidPaymentHistory.stateKind(state)}, event=${InvalidPaymentHistory.eventKind(event)}, reason=$reason"
    )

object InvalidPaymentHistory:
  def stateKind(state: PaymentState): String =
    state.productPrefix

  def eventKind(event: PaymentEvent): String =
    event.productPrefix
