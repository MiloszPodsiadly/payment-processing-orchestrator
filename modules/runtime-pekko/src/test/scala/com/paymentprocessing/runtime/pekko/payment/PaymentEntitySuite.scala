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
import com.paymentprocessing.domain.payment.Payment
import com.paymentprocessing.domain.payment.PaymentCommand
import com.paymentprocessing.domain.payment.PaymentError
import com.paymentprocessing.domain.payment.PaymentEvent
import com.paymentprocessing.domain.payment.PaymentState
import com.typesafe.config.ConfigFactory
import munit.FunSuite
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.persistence.testkit.PersistenceTestKitPlugin
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin
import org.apache.pekko.persistence.testkit.scaladsl.PersistenceTestKit

import java.time.Instant
import java.util.UUID
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

final class PaymentEntitySuite extends FunSuite:
  private val now = Instant.parse("2026-01-01T00:00:00Z")
  private var actorTestKit: ActorTestKit = uninitialized
  private var persistenceTestKit: PersistenceTestKit = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    actorTestKit = ActorTestKit(
      s"PaymentEntitySuite-${UUID.randomUUID()}",
      testKitConfig
    )
    persistenceTestKit = PersistenceTestKit(actorTestKit.system)
    persistenceTestKit.clearAll()

  override def afterEach(context: AfterEach): Unit =
    if actorTestKit != null then actorTestKit.shutdownTestKit()

  test("create persists PaymentCreated and recovers Created state") {
    val entity = spawn(payment.paymentId)
    val expectedEvent = PaymentEvent.PaymentCreated(
      payment.paymentId,
      payment.tenantId,
      payment.customerId,
      payment.merchantId,
      payment.amount,
      payment.paymentMethodToken,
      payment.createdAt
    )

    val reply = execute(entity, createCommand())

    assertEquals(reply, PaymentEntity.Accepted(PaymentState.Created(payment), List(expectedEvent)))
    assertEquals(persistedEvents(payment.paymentId), List(expectedEvent))

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.error, PaymentError.PaymentAlreadyCreated)
    assertEquals(recoveredReply.state, PaymentState.Created(payment))
  }

  test("invalid command persists zero events and leaves NotCreated state") {
    val entity = spawn(payment.paymentId)

    val reply = execute(entity, PaymentCommand.CapturePayment(operation("capture-1"), now))

    assertEquals(
      reply,
      PaymentEntity.Rejected(PaymentError.PaymentNotCreated, PaymentState.NotCreated)
    )
    assertEquals(persistedEvents(payment.paymentId), Nil)
  }

  test("duplicate-safe authorization command persists zero additional events") {
    val entity = spawn(payment.paymentId)
    val beforeDuplicate =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now)
        )
      )

    val duplicateReply =
      execute(entity, PaymentCommand.AuthorizePayment(operation("auth-1"), now))

    assertEquals(duplicateReply, PaymentEntity.DuplicateAccepted(beforeDuplicate))
    assertEquals(
      persistedEvents(payment.paymentId),
      List(
        PaymentEvent.PaymentCreated(
          payment.paymentId,
          payment.tenantId,
          payment.customerId,
          payment.merchantId,
          payment.amount,
          payment.paymentMethodToken,
          payment.createdAt
        ),
        PaymentEvent.FraudCheckRequested(now),
        PaymentEvent.FraudCheckPassed(now),
        PaymentEvent.AuthorizationRequested(operation("auth-1"), now)
      )
    )
  }

  test("authorization flow persists domain events in order") {
    val entity = spawn(payment.paymentId)

    val finalState =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
        )
      )

    assertEquals(finalState.productPrefix, "Authorized")
    assertEquals(
      persistedEvents(payment.paymentId).map(_.productPrefix),
      List(
        "PaymentCreated",
        "FraudCheckRequested",
        "FraudCheckPassed",
        "AuthorizationRequested",
        "PaymentAuthorized"
      )
    )
  }

  test("capture flow persists one capture request and one capture success") {
    val entity = spawn(payment.paymentId)

    val finalState =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now),
          PaymentCommand.CapturePayment(operation("capture-1"), now),
          PaymentCommand.RecordCaptureSucceeded(operation("capture-1"), now)
        )
      )

    assertEquals(finalState.productPrefix, "Captured")
    assertEquals(
      persistedEvents(payment.paymentId).map(_.productPrefix),
      List(
        "PaymentCreated",
        "FraudCheckRequested",
        "FraudCheckPassed",
        "AuthorizationRequested",
        "PaymentAuthorized",
        "CaptureRequested",
        "PaymentCaptured"
      )
    )
  }

  test("authorized state recovers structurally") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
        )
      )

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(stateBeforeRestart.productPrefix, "Authorized")
    assertEquals(recoveredReply.state, stateBeforeRestart)
  }

  test("captured state recovers structurally") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart = executeFlow(entity, capturedCommands)

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(stateBeforeRestart.productPrefix, "Captured")
    assertEquals(recoveredReply.state, stateBeforeRestart)
  }

  test("unknown authorization state recovers with exact provider operation identity") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-unknown"), now),
          PaymentCommand.RecordAuthorizationUnknown(operation("auth-unknown"), now)
        )
      )

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.state, stateBeforeRestart)
    recoveredReply.state match
      case PaymentState.AuthorizationUnknown(_, operationId) =>
        assertEquals(operationId, operation("auth-unknown"))
      case other =>
        fail(s"Expected AuthorizationUnknown, got ${other.productPrefix}")
  }

  test("capture unknown state recovers with exact provider operation identity") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now),
          PaymentCommand.CapturePayment(operation("capture-unknown"), now),
          PaymentCommand.RecordCaptureUnknown(operation("capture-unknown"), now)
        )
      )

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.state, stateBeforeRestart)
    recoveredReply.state match
      case PaymentState.CaptureUnknown(_, _, operationId) =>
        assertEquals(operationId, operation("capture-unknown"))
      case other =>
        fail(s"Expected CaptureUnknown, got ${other.productPrefix}")
  }

  test("partial refund recovery preserves ledger and operation identity") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart =
      executeFlow(
        entity,
        capturedCommands ++ List(
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("30.00"), now),
          PaymentCommand.RecordRefundSucceeded(operation("refund-1"), now)
        )
      )

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.state, stateBeforeRestart)
    recoveredReply.state match
      case PaymentState.PartiallyRefunded(_, authorization, capture, refunds) =>
        assertEquals(authorization.operationId, operation("auth-1"))
        assertEquals(capture.operationId, operation("capture-1"))
        assertEquals(refunds.map(_.refundId), List(refundId(1)))
        assertEquals(refunds.map(_.operationId), List(operation("refund-1")))
        assertEquals(refunds.map(_.amount), List(money("30.00")))
      case other =>
        fail(s"Expected PartiallyRefunded, got ${other.productPrefix}")
  }

  test("refund unknown state recovers with exact pending refund identity") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart =
      executeFlow(
        entity,
        capturedCommands ++ List(
          PaymentCommand
            .RefundPayment(refundId(1), operation("refund-unknown"), money("30.00"), now),
          PaymentCommand.RecordRefundUnknown(operation("refund-unknown"), now)
        )
      )

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.state, stateBeforeRestart)
    recoveredReply.state match
      case PaymentState.RefundUnknown(_, _, _, completedRefunds, pending) =>
        assertEquals(completedRefunds, Nil)
        assertEquals(pending.refundId, refundId(1))
        assertEquals(pending.operationId, operation("refund-unknown"))
        assertEquals(pending.amount, money("30.00"))
      case other =>
        fail(s"Expected RefundUnknown, got ${other.productPrefix}")
  }

  test("full refund recovery preserves exact refunded state") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart =
      executeFlow(
        entity,
        capturedCommands ++ List(
          PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("100.00"), now),
          PaymentCommand.RecordRefundSucceeded(operation("refund-1"), now)
        )
      )

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.state, stateBeforeRestart)
    assertEquals(recoveredReply.state.productPrefix, "Refunded")
  }

  test("duplicate-safe capture command persists zero additional events") {
    val entity = spawn(payment.paymentId)
    val capturePending =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now),
          PaymentCommand.CapturePayment(operation("capture-1"), now)
        )
      )
    val before = persistedEvents(payment.paymentId)

    val duplicateReply = execute(entity, PaymentCommand.CapturePayment(operation("capture-1"), now))

    assertEquals(duplicateReply, PaymentEntity.DuplicateAccepted(capturePending))
    assertEquals(persistedEvents(payment.paymentId), before)
  }

  test("duplicate-safe refund command persists zero additional events") {
    val entity = spawn(payment.paymentId)
    val refundPending =
      executeFlow(
        entity,
        capturedCommands :+ PaymentCommand.RefundPayment(
          refundId(1),
          operation("refund-1"),
          money("30.00"),
          now
        )
      )
    val before = persistedEvents(payment.paymentId)

    val duplicateReply =
      execute(
        entity,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("30.00"), now)
      )

    assertEquals(duplicateReply, PaymentEntity.DuplicateAccepted(refundPending))
    assertEquals(persistedEvents(payment.paymentId), before)
  }

  test("provider operation reuse rejection persists zero additional events") {
    val entity = spawn(payment.paymentId)
    val authorized =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
        )
      )
    val before = persistedEvents(payment.paymentId)

    val reply = execute(entity, PaymentCommand.CapturePayment(operation("auth-1"), now))

    assertEquals(
      reply,
      PaymentEntity.Rejected(
        PaymentError.ProviderOperationAlreadyUsed(operation("auth-1")),
        authorized
      )
    )
    assertEquals(persistedEvents(payment.paymentId), before)
  }

  test("entity identity mismatch is rejected before persistence") {
    val entityPaymentId =
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000901"))
    val wrongPayment = paymentWithId(
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000902"))
    )
    val entity = spawn(entityPaymentId)

    val mismatchReply = execute(entity, createCommand(wrongPayment))

    assertEquals(
      mismatchReply,
      PaymentEntity.InvalidEnvelope(
        PaymentEntity.InvalidEnvelopeReason.PaymentIdMismatch(
          entityPaymentId,
          wrongPayment.paymentId
        ),
        PaymentState.NotCreated
      )
    )
    assertEquals(persistedEvents(entityPaymentId), Nil)
    assertEquals(
      execute(entity, PaymentCommand.CapturePayment(operation("capture-1"), now)),
      PaymentEntity.Rejected(PaymentError.PaymentNotCreated, PaymentState.NotCreated)
    )
  }

  test("persistence id is deterministic, stable and payment-specific") {
    val paymentIdA =
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000911"))
    val paymentIdB =
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000912"))

    assertEquals(
      PaymentEntity.persistenceId(paymentIdA),
      "payment|00000000-0000-0000-0000-000000000911"
    )
    assertEquals(PaymentEntity.persistenceId(paymentIdA), PaymentEntity.persistenceId(paymentIdA))
    assertNotEquals(
      PaymentEntity.persistenceId(paymentIdA),
      PaymentEntity.persistenceId(paymentIdB)
    )
  }

  test("rapid conflicting capture commands serialize into one logical capture intent") {
    val entity = spawn(payment.paymentId)
    val authorized =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now),
          PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now)
        )
      )
    val probe = actorTestKit.createTestProbe[PaymentEntity.Reply]()

    (1 to 100).foreach { index =>
      entity ! PaymentEntity.Execute(
        PaymentCommand.CapturePayment(operation(s"capture-$index"), now),
        probe.ref
      )
    }

    val replies = probe.receiveMessages(100, 5.seconds)

    assertEquals(replies.count(_.isInstanceOf[PaymentEntity.Accepted]), 1)
    assertEquals(replies.count(_.isInstanceOf[PaymentEntity.Rejected]), 99)
    assertEquals(
      persistedEvents(payment.paymentId).map(_.productPrefix),
      List(
        "PaymentCreated",
        "FraudCheckRequested",
        "FraudCheckPassed",
        "AuthorizationRequested",
        "PaymentAuthorized",
        "CaptureRequested"
      )
    )
    assert(replies.exists(_.state != authorized))
  }

  test("journal write failure does not produce false accepted reply") {
    val entity = spawn(payment.paymentId)
    val replyProbe = actorTestKit.createTestProbe[PaymentEntity.Reply]()
    val deathProbe = actorTestKit.createTestProbe[Nothing]()

    persistenceTestKit.failNextPersisted(
      PaymentEntity.persistenceId(payment.paymentId),
      new RuntimeException("simulated journal failure")
    )
    entity ! PaymentEntity.Execute(createCommand(), replyProbe.ref)

    replyProbe.expectNoMessage(500.millis)
    deathProbe.expectTerminated(entity, 5.seconds)
    assertEquals(persistedEvents(payment.paymentId), Nil)
  }

  test("corrupt recovery history fails loudly instead of fabricating state") {
    val replyProbe = actorTestKit.createTestProbe[PaymentEntity.Reply]()
    val deathProbe = actorTestKit.createTestProbe[Nothing]()

    persistenceTestKit.persistForRecovery(
      PaymentEntity.persistenceId(payment.paymentId),
      List(PaymentEvent.PaymentCaptured(operation("capture-corrupt"), now))
    )
    val entity = spawn(payment.paymentId)
    entity ! PaymentEntity.Execute(createCommand(), replyProbe.ref)

    replyProbe.expectNoMessage(500.millis)
    deathProbe.expectTerminated(entity, 5.seconds)
  }

  private def spawn(paymentId: PaymentId): ActorRef[PaymentEntity.Command] =
    actorTestKit.spawn(PaymentEntity(paymentId), s"payment-${UUID.randomUUID()}")

  private def execute(
      entity: ActorRef[PaymentEntity.Command],
      command: PaymentCommand
  ): PaymentEntity.Reply =
    val probe = actorTestKit.createTestProbe[PaymentEntity.Reply]()
    val deathProbe = actorTestKit.createTestProbe[Nothing]()
    entity ! PaymentEntity.Execute(command, probe.ref)
    try probe.receiveMessage(5.seconds)
    catch
      case error: AssertionError =>
        val terminated =
          try
            deathProbe.expectTerminated(entity, 500.millis)
            true
          catch case _: AssertionError => false
        throw new AssertionError(
          s"Timed out waiting for PaymentEntity reply; actorTerminated=$terminated",
          error
        )

  private def executeFlow(
      entity: ActorRef[PaymentEntity.Command],
      commands: List[PaymentCommand]
  ): PaymentState =
    commands.foldLeft(PaymentState.NotCreated) { case (_, command) =>
      execute(entity, command) match
        case accepted: PaymentEntity.Accepted => accepted.state
        case duplicate: PaymentEntity.DuplicateAccepted => duplicate.state
        case rejected: PaymentEntity.Rejected =>
          fail(s"Unexpected domain rejection: ${rejected.error}")
        case invalid: PaymentEntity.InvalidEnvelope =>
          fail(s"Unexpected envelope rejection: ${invalid.reason}")
    }

  private def restartAndRejectCreate(
      entity: ActorRef[PaymentEntity.Command],
      paymentId: PaymentId
  ): PaymentEntity.Rejected =
    actorTestKit.stop(entity)
    val recovered = spawn(paymentId)
    execute(recovered, createCommand()) match
      case rejected: PaymentEntity.Rejected => rejected
      case other => fail(s"Expected rejected duplicate create after recovery, got $other")

  private def persistedEvents(paymentId: PaymentId): List[PaymentEvent] =
    persistenceTestKit
      .persistedInStorage(PaymentEntity.persistenceId(paymentId))
      .collect { case event: PaymentEvent => event }
      .toList

  private def capturedCommands: List[PaymentCommand] =
    List(
      createCommand(),
      PaymentCommand.StartFraudCheck(now),
      PaymentCommand.RecordFraudApproved(now),
      PaymentCommand.AuthorizePayment(operation("auth-1"), now),
      PaymentCommand.RecordAuthorizationSucceeded(operation("auth-1"), now),
      PaymentCommand.CapturePayment(operation("capture-1"), now),
      PaymentCommand.RecordCaptureSucceeded(operation("capture-1"), now)
    )

  private def createCommand(source: Payment = payment): PaymentCommand.CreatePayment =
    PaymentCommand.CreatePayment(
      source.paymentId,
      source.tenantId,
      source.customerId,
      source.merchantId,
      source.amount,
      source.paymentMethodToken,
      source.createdAt
    )

  private def payment: Payment =
    paymentWithId(PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000801")))

  private def paymentWithId(paymentId: PaymentId): Payment =
    Payment(
      paymentId,
      TenantId.from(UUID.fromString("00000000-0000-0000-0000-000000000802")),
      CustomerId.from(UUID.fromString("00000000-0000-0000-0000-000000000803")),
      MerchantId.from(UUID.fromString("00000000-0000-0000-0000-000000000804")),
      money("100.00"),
      PaymentMethodToken.from("tok_runtime_1").fold(error => fail(error.toString), identity),
      now
    )

  private def refundId(index: Int): RefundId =
    RefundId.from(UUID.fromString(f"00000000-0000-0000-0000-$index%012d"))

  private def operation(value: String): ProviderOperationId =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)

  private def money(value: String): Money =
    Money.from(BigDecimal(value), Currency.PLN).fold(error => fail(error.toString), identity)

  private def testKitConfig =
    ConfigFactory
      .parseString(
        """
          |pekko.persistence.journal.plugin = "pekko.persistence.testkit.journal"
          |pekko.persistence.snapshot-store.plugin = "pekko.persistence.testkit.snapshotstore.pluginid"
          |pekko.persistence.testkit.events.serialize = false
          |pekko.persistence.testkit.snapshots.serialize = false
          |""".stripMargin
      )
      .withFallback(PersistenceTestKitPlugin.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())
