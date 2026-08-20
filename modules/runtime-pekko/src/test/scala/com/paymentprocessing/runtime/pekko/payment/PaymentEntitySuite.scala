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
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.serialization.SerializerWithStringManifest

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.NotSerializableException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt
import scala.deriving.Mirror

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
    val expectedEvent = createdEvent(payment)

    val reply = execute(entity, createCommand())

    assertEquals(reply, PaymentEntity.Accepted(PaymentState.Created(payment), List(expectedEvent)))
    assertEquals(persistedEvents(payment.paymentId), List(expectedEvent))

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(recoveredReply.error, PaymentError.PaymentAlreadyCreated)
    assertEquals(recoveredReply.state, PaymentState.Created(payment))
  }

  test("PaymentEvent serializer contract is inherited from runtime reference.conf") {
    val testApplicationConf = testApplicationConfText

    assert(!testApplicationConf.contains("PaymentEventSerializer"))
    assert(!testApplicationConf.contains("serialization-bindings"))
    assert(!testApplicationConf.contains("allow-java-serialization"))

    val config = actorTestKit.system.settings.config

    assertEquals(
      config.getString("pekko.actor.serializers.payment-event"),
      classOf[PaymentEventSerializer].getName
    )
    assertEquals(
      config.getString(
        "pekko.actor.serialization-bindings.\"com.paymentprocessing.domain.payment.PaymentEvent\""
      ),
      "payment-event"
    )
    assert(!config.getBoolean("pekko.actor.allow-java-serialization"))
    assertEquals(
      SerializationExtension(actorTestKit.system).findSerializerFor(createdEvent(payment)).getClass,
      classOf[PaymentEventSerializer]
    )
  }

  test("PaymentEvent serializer fixtures cover every concrete PaymentEvent case") {
    val compilerDerivedLabels = paymentEventCaseLabels
    val fixtureLabels = samplePaymentEvents.map(_.productPrefix).toSet

    assertEquals(compilerDerivedLabels, fixtureLabels)
    assertEquals(samplePaymentEvents.size, fixtureLabels.size)
  }

  test("PaymentEvent serialization does not fall back to Java serialization") {
    assert(!actorTestKit.system.settings.config.getBoolean("pekko.actor.allow-java-serialization"))

    val serializer =
      SerializationExtension(actorTestKit.system).findSerializerFor(createdEvent(payment))

    assertEquals(serializer.getClass, classOf[PaymentEventSerializer])
    assertNotEquals(serializer.getClass.getName, "org.apache.pekko.serialization.JavaSerializer")
  }

  test("PaymentEvent serialization binding round-trips every concrete event") {
    samplePaymentEvents.foreach { event =>
      assertEquals(roundTripPaymentEvent(event), event)
    }
  }

  test("PaymentEventSerializer round-trips legal strings beyond writeUTF size") {
    val longProviderOperationId = operation("op_" + ("a" * 70000))
    val longPaymentMethodToken =
      PaymentMethodToken.from("tok_" + ("b" * 70000)).fold(error => fail(error.toString), identity)
    val longTokenPayment =
      Payment(
        payment.paymentId,
        payment.tenantId,
        payment.customerId,
        payment.merchantId,
        payment.amount,
        longPaymentMethodToken,
        payment.createdAt
      )
    val providerEvent = PaymentEvent.PaymentCaptured(longProviderOperationId, now)
    val createdWithLongToken = createdEvent(longTokenPayment)

    assertEquals(roundTripPaymentEvent(providerEvent), providerEvent)
    assertEquals(roundTripPaymentEvent(createdWithLongToken), createdWithLongToken)
  }

  test("PaymentEventSerializer round-trips non-ASCII UTF-8 strings") {
    val unicodeOperation = operation("op-zolc-测试-مرحبا")
    val unicodeToken =
      PaymentMethodToken
        .from("tok-zolc-测试-مرحبا")
        .fold(error => fail(error.toString), identity)
    val unicodePayment =
      Payment(
        payment.paymentId,
        payment.tenantId,
        payment.customerId,
        payment.merchantId,
        payment.amount,
        unicodeToken,
        payment.createdAt
      )

    val providerEvent = PaymentEvent.AuthorizationRequested(unicodeOperation, now)
    val createdWithUnicodeToken = createdEvent(unicodePayment)

    assertEquals(roundTripPaymentEvent(providerEvent), providerEvent)
    assertEquals(roundTripPaymentEvent(createdWithUnicodeToken), createdWithUnicodeToken)
  }

  test("PaymentEventSerializer rejects malformed payloads deterministically") {
    val serializer = paymentEventSerializerFor(createdEvent(payment))
    val manifest = serializer.manifest(createdEvent(payment))
    val validPayload = serializer.toBinary(createdEvent(payment))

    val negativeLengthFailure = intercept[NotSerializableException] {
      serializer.fromBinary(
        encodedPayload { out =>
          out.writeInt(1)
          out.writeInt(-1)
        },
        manifest
      )
    }
    assert(negativeLengthFailure.getMessage.contains("Negative PaymentEvent tag length"))

    val impossibleLengthFailure = intercept[NotSerializableException] {
      serializer.fromBinary(
        encodedPayload { out =>
          out.writeInt(1)
          out.writeInt(16)
          out.write(Array[Byte](1, 2, 3, 4))
        },
        manifest
      )
    }
    assert(impossibleLengthFailure.getMessage.contains("exceeds remaining PaymentEvent payload"))

    val trailingBytesFailure = intercept[NotSerializableException] {
      serializer.fromBinary(validPayload :+ 1.toByte, manifest)
    }
    assert(trailingBytesFailure.getMessage.contains("Unexpected trailing bytes"))
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
        createdEvent(payment),
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

  test("authorization pending recovers exact operation identity and duplicate semantics") {
    val entity = spawn(payment.paymentId)
    val pendingBeforeRestart =
      executeFlow(
        entity,
        List(
          createCommand(),
          PaymentCommand.StartFraudCheck(now),
          PaymentCommand.RecordFraudApproved(now),
          PaymentCommand.AuthorizePayment(operation("auth-1"), now)
        )
      )

    actorTestKit.stop(entity)
    val recovered = spawn(payment.paymentId)
    val beforeDuplicate = persistedEvents(payment.paymentId)

    val duplicateReply =
      execute(recovered, PaymentCommand.AuthorizePayment(operation("auth-1"), now))

    assertEquals(duplicateReply, PaymentEntity.DuplicateAccepted(pendingBeforeRestart))
    assertEquals(persistedEvents(payment.paymentId), beforeDuplicate)

    val beforeConflict = persistedEvents(payment.paymentId)
    val conflictReply =
      execute(recovered, PaymentCommand.AuthorizePayment(operation("auth-2"), now))

    assertEquals(
      conflictReply,
      PaymentEntity.Rejected(
        PaymentError.OperationMismatch(operation("auth-1"), operation("auth-2")),
        pendingBeforeRestart
      )
    )
    assertEquals(persistedEvents(payment.paymentId), beforeConflict)
  }

  test("captured state recovers structurally") {
    val entity = spawn(payment.paymentId)
    val stateBeforeRestart = executeFlow(entity, capturedCommands)

    val recoveredReply = restartAndRejectCreate(entity, payment.paymentId)

    assertEquals(stateBeforeRestart.productPrefix, "Captured")
    assertEquals(recoveredReply.state, stateBeforeRestart)
  }

  test("capture pending recovers exact operation identity and duplicate semantics") {
    val entity = spawn(payment.paymentId)
    val pendingBeforeRestart =
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

    actorTestKit.stop(entity)
    val recovered = spawn(payment.paymentId)
    val beforeDuplicate = persistedEvents(payment.paymentId)

    val duplicateReply =
      execute(recovered, PaymentCommand.CapturePayment(operation("capture-1"), now))

    assertEquals(duplicateReply, PaymentEntity.DuplicateAccepted(pendingBeforeRestart))
    assertEquals(persistedEvents(payment.paymentId), beforeDuplicate)

    val beforeConflict = persistedEvents(payment.paymentId)
    val conflictReply =
      execute(recovered, PaymentCommand.CapturePayment(operation("capture-2"), now))

    assertEquals(
      conflictReply,
      PaymentEntity.Rejected(
        PaymentError.OperationMismatch(operation("capture-1"), operation("capture-2")),
        pendingBeforeRestart
      )
    )
    assertEquals(persistedEvents(payment.paymentId), beforeConflict)
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

  test("refund pending recovers exact pending identity and duplicate semantics") {
    val entity = spawn(payment.paymentId)
    val pendingBeforeRestart =
      executeFlow(
        entity,
        capturedCommands :+ PaymentCommand.RefundPayment(
          refundId(1),
          operation("refund-1"),
          money("30.00"),
          now
        )
      )

    actorTestKit.stop(entity)
    val recovered = spawn(payment.paymentId)
    val beforeDuplicate = persistedEvents(payment.paymentId)

    val duplicateReply =
      execute(
        recovered,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-1"), money("30.00"), now)
      )

    assertEquals(duplicateReply, PaymentEntity.DuplicateAccepted(pendingBeforeRestart))
    assertEquals(persistedEvents(payment.paymentId), beforeDuplicate)

    val beforeChangedSameRefund = persistedEvents(payment.paymentId)
    val changedSameRefundReply =
      execute(
        recovered,
        PaymentCommand.RefundPayment(refundId(1), operation("refund-2"), money("31.00"), now)
      )

    assertEquals(
      changedSameRefundReply,
      PaymentEntity.Rejected(
        PaymentError.DuplicateRefundConflict(refundId(1)),
        pendingBeforeRestart
      )
    )
    assertEquals(persistedEvents(payment.paymentId), beforeChangedSameRefund)

    val beforeNewRefund = persistedEvents(payment.paymentId)
    val newRefundReply =
      execute(
        recovered,
        PaymentCommand.RefundPayment(refundId(2), operation("refund-2"), money("1.00"), now)
      )

    assertEquals(
      newRefundReply,
      PaymentEntity.Rejected(
        PaymentError.OperationMismatch(operation("refund-1"), operation("refund-2")),
        pendingBeforeRestart
      )
    )
    assertEquals(persistedEvents(payment.paymentId), beforeNewRefund)
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

  test("cross-payment PaymentCreated in journal fails recovery before command handling") {
    val entityPaymentId =
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000921"))
    val wrongPayment = paymentWithId(
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000922"))
    )
    val replyProbe = actorTestKit.createTestProbe[PaymentEntity.Reply]()
    val deathProbe = actorTestKit.createTestProbe[Nothing]()

    persistenceTestKit.persistForRecovery(
      PaymentEntity.persistenceId(entityPaymentId),
      List(createdEvent(wrongPayment))
    )
    val entity = spawn(entityPaymentId)
    val beforeCommand = persistedEvents(entityPaymentId)

    entity ! PaymentEntity.Execute(PaymentCommand.StartFraudCheck(now), replyProbe.ref)

    replyProbe.expectNoMessage(500.millis)
    deathProbe.expectTerminated(entity, 5.seconds)
    assertEquals(persistedEvents(entityPaymentId), beforeCommand)
  }

  test("matching PaymentCreated in journal recovers and accepts subsequent legal command") {
    val entityPayment = paymentWithId(
      PaymentId.from(UUID.fromString("00000000-0000-0000-0000-000000000923"))
    )

    persistenceTestKit.persistForRecovery(
      PaymentEntity.persistenceId(entityPayment.paymentId),
      List(createdEvent(entityPayment))
    )
    val entity = spawn(entityPayment.paymentId)

    val reply = execute(entity, PaymentCommand.StartFraudCheck(now))

    assertEquals(
      reply,
      PaymentEntity.Accepted(
        PaymentState.FraudCheckPending(entityPayment),
        List(PaymentEvent.FraudCheckRequested(now))
      )
    )
    assertEquals(
      persistedEvents(entityPayment.paymentId),
      List(createdEvent(entityPayment), PaymentEvent.FraudCheckRequested(now))
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

    val acceptedReplies = replies.collect { case accepted: PaymentEntity.Accepted => accepted }
    val rejectedReplies = replies.collect { case rejected: PaymentEntity.Rejected => rejected }

    assertEquals(acceptedReplies.size, 1)
    assertEquals(rejectedReplies.size, 99)
    assert(rejectedReplies.forall(_.error match
      case PaymentError.OperationMismatch(_, _) => true
      case _ => false))
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

  private def roundTripPaymentEvent(event: PaymentEvent): PaymentEvent =
    val serializer = paymentEventSerializerFor(event)
    val manifest = serializer.manifest(event)
    val bytes = serializer.toBinary(event)

    serializer.fromBinary(bytes, manifest).asInstanceOf[PaymentEvent]

  private def paymentEventSerializerFor(event: PaymentEvent): SerializerWithStringManifest =
    val serializer = SerializationExtension(actorTestKit.system).findSerializerFor(event)

    assertEquals(serializer.getClass, classOf[PaymentEventSerializer])
    serializer match
      case serializerWithManifest: SerializerWithStringManifest =>
        serializerWithManifest
      case other =>
        fail(s"Expected SerializerWithStringManifest, got ${other.getClass.getName}")

  private def encodedPayload(write: DataOutputStream => Unit): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    val out = DataOutputStream(bytes)
    write(out)
    out.flush()
    bytes.toByteArray

  private def testApplicationConfText: String =
    val stream = Option(getClass.getClassLoader.getResourceAsStream("application.conf"))
      .getOrElse(fail("Missing test application.conf resource"))

    try String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  private def paymentEventCaseLabels: Set[String] =
    labelsOf[PaymentEvent]

  private inline def labelsOf[T](using mirror: Mirror.SumOf[T]): Set[String] =
    labelsFromTuple[mirror.MirroredElemLabels]

  private inline def labelsFromTuple[Labels <: Tuple]: Set[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Set.empty
      case _: (head *: tail) =>
        Set(constValue[head].asInstanceOf[String]) ++ labelsFromTuple[tail]

  private def samplePaymentEvents: List[PaymentEvent] =
    List(
      createdEvent(payment),
      PaymentEvent.FraudCheckRequested(now),
      PaymentEvent.FraudCheckPassed(now),
      PaymentEvent.FraudCheckRejected(now),
      PaymentEvent.FraudManualReviewRequired(now),
      PaymentEvent.FraudManualReviewApproved(now),
      PaymentEvent.FraudManualReviewRejected(now),
      PaymentEvent.AuthorizationRequested(operation("auth-1"), now),
      PaymentEvent.PaymentAuthorized(operation("auth-1"), now),
      PaymentEvent.PaymentDeclined(operation("auth-1"), now),
      PaymentEvent.AuthorizationOutcomeUnknown(operation("auth-1"), now),
      PaymentEvent.CaptureRequested(operation("capture-1"), now),
      PaymentEvent.PaymentCaptured(operation("capture-1"), now),
      PaymentEvent.CaptureFailed(operation("capture-1"), now),
      PaymentEvent.CaptureOutcomeUnknown(operation("capture-1"), now),
      PaymentEvent.RefundRequested(refundId(1), operation("refund-1"), money("30.00"), now),
      PaymentEvent.PaymentPartiallyRefunded(
        refundId(1),
        operation("refund-1"),
        money("30.00"),
        now
      ),
      PaymentEvent.PaymentRefunded(refundId(1), operation("refund-1"), money("100.00"), now),
      PaymentEvent.RefundFailed(operation("refund-1"), now),
      PaymentEvent.RefundOutcomeUnknown(operation("refund-1"), now)
    )

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

  private def createdEvent(source: Payment): PaymentEvent.PaymentCreated =
    PaymentEvent.PaymentCreated(
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
    PersistenceTestKitPlugin.config
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())
