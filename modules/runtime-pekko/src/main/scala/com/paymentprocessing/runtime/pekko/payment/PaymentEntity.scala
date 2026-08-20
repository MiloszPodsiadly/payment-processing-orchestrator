package com.paymentprocessing.runtime.pekko.payment

import com.paymentprocessing.domain.identity.PaymentId
import com.paymentprocessing.domain.payment.PaymentCommand
import com.paymentprocessing.domain.payment.PaymentDecider
import com.paymentprocessing.domain.payment.PaymentError
import com.paymentprocessing.domain.payment.PaymentEvent
import com.paymentprocessing.domain.payment.PaymentState
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.Effect
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior
import org.apache.pekko.persistence.typed.scaladsl.ReplyEffect

object PaymentEntity:
  sealed trait Command

  final case class Execute(
      command: PaymentCommand,
      replyTo: ActorRef[Reply]
  ) extends Command

  sealed trait Reply:
    def state: PaymentState

  final case class Accepted(
      state: PaymentState,
      persistedEvents: List[PaymentEvent]
  ) extends Reply

  final case class DuplicateAccepted(state: PaymentState) extends Reply

  final case class Rejected(
      error: PaymentError,
      state: PaymentState
  ) extends Reply

  final case class InvalidEnvelope(
      reason: InvalidEnvelopeReason,
      state: PaymentState
  ) extends Reply

  enum InvalidEnvelopeReason:
    case PaymentIdMismatch(expected: PaymentId, actual: PaymentId)

  def apply(paymentId: PaymentId): Behavior[Command] =
    EventSourcedBehavior.withEnforcedReplies[Command, PaymentEvent, PaymentState](
      persistenceId = pekkoPersistenceId(paymentId),
      emptyState = PaymentState.NotCreated,
      commandHandler = commandHandler(paymentId),
      eventHandler = eventHandler(paymentId)
    )

  def persistenceId(paymentId: PaymentId): String =
    pekkoPersistenceId(paymentId).id

  private def pekkoPersistenceId(paymentId: PaymentId): PersistenceId =
    PersistenceId.of("payment", paymentId.value.toString)

  private def commandHandler(
      paymentId: PaymentId
  )(state: PaymentState, command: Command): ReplyEffect[PaymentEvent, PaymentState] =
    command match
      case Execute(domainCommand, replyTo) =>
        validateEnvelope(paymentId, domainCommand) match
          case Some(reason) =>
            Effect.reply(replyTo)(InvalidEnvelope(reason, state))
          case None =>
            PaymentDecider.decide(state, domainCommand) match
              case Left(error) =>
                Effect.reply(replyTo)(Rejected(error, state))
              case Right(Nil) =>
                Effect.reply(replyTo)(DuplicateAccepted(state))
              case Right(events) =>
                Effect
                  .persist(events)
                  .thenReply(replyTo)(updatedState => Accepted(updatedState, events))

  private def validateEnvelope(
      paymentId: PaymentId,
      command: PaymentCommand
  ): Option[InvalidEnvelopeReason] =
    command match
      case create: PaymentCommand.CreatePayment if create.paymentId != paymentId =>
        Some(InvalidEnvelopeReason.PaymentIdMismatch(paymentId, create.paymentId))
      case _ =>
        None

  private def eventHandler(
      entityPaymentId: PaymentId
  )(state: PaymentState, event: PaymentEvent): PaymentState =
    validatePersistedEntityIdentity(entityPaymentId, state, event)
    PaymentDecider.evolve(state, event)

  private def validatePersistedEntityIdentity(
      entityPaymentId: PaymentId,
      state: PaymentState,
      event: PaymentEvent
  ): Unit =
    (state, event) match
      case (PaymentState.NotCreated, created: PaymentEvent.PaymentCreated)
          if created.paymentId != entityPaymentId =>
        throw InvalidPaymentEntityHistory(
          expected = entityPaymentId,
          actual = created.paymentId,
          eventKind = created.productPrefix,
          reason = "PaymentCreated paymentId does not match PaymentEntity identity"
        )
      case _ =>
        ()

final case class InvalidPaymentEntityHistory(
    expected: PaymentId,
    actual: PaymentId,
    eventKind: String,
    reason: String
) extends IllegalStateException(
      s"Invalid payment entity history: expectedPaymentId=${expected.value}, actualPaymentId=${actual.value}, event=$eventKind, reason=$reason"
    )
