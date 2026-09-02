package com.paymentprocessing.persistence.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.paymentprocessing.adapter.cassandra.CassandraJournalSchema
import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupError
import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupValidator
import com.paymentprocessing.domain.identity.CustomerId
import com.paymentprocessing.domain.identity.MerchantId
import com.paymentprocessing.domain.identity.PaymentId
import com.paymentprocessing.domain.identity.PaymentMethodToken
import com.paymentprocessing.domain.identity.ProviderOperationId
import com.paymentprocessing.domain.identity.TenantId
import com.paymentprocessing.domain.money.Currency
import com.paymentprocessing.domain.money.Money
import com.paymentprocessing.domain.payment.Payment
import com.paymentprocessing.domain.payment.PaymentCommand
import com.paymentprocessing.domain.payment.PaymentError
import com.paymentprocessing.domain.payment.PaymentState
import com.paymentprocessing.runtime.pekko.payment.PaymentEntity
import com.paymentprocessing.runtime.pekko.payment.PaymentEventSerializer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import munit.FunSuite
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.testcontainers.cassandra.CassandraContainer

import java.time.Instant
import java.util.UUID
import scala.compiletime.uninitialized
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

final class PaymentEntityCassandraPersistenceSuite extends FunSuite:
  private val now = Instant.parse("2026-01-01T00:00:00Z")
  private var cassandra: CassandraContainer = uninitialized
  private var cqlSession: CqlSession = uninitialized

  override def beforeAll(): Unit =
    cassandra = new CassandraContainer("cassandra:5.0.8")
    cassandra.start()
    cqlSession =
      CqlSession
        .builder()
        .addContactPoint(cassandra.getContactPoint)
        .withLocalDatacenter(cassandra.getLocalDatacenter)
        .build()
    CassandraJournalSchema.applyMigration(cqlSession)

  override def afterAll(): Unit =
    if cqlSession != null then cqlSession.close()
    if cassandra != null then cassandra.stop()

  test("migration creates the required Pekko Cassandra journal schema and is idempotent") {
    CassandraJournalSchema.applyMigration(cqlSession)

    assert(CassandraJournalSchema.tableNames(cqlSession).contains("messages"))
    assertEquals(
      CassandraJournalSchema.RequiredTables.diff(CassandraJournalSchema.tableNames(cqlSession)),
      Set.empty
    )
    assertEquals(
      CassandraJournalSchema.RequiredMessagesColumns.diff(
        CassandraJournalSchema.messagesColumns(cqlSession)
      ),
      Set.empty
    )
  }

  test("startup validation accepts only the prepared Cassandra journal schema") {
    val kit = actorTestKit()
    try
      given ExecutionContext = kit.system.executionContext

      val result =
        Await.result(CassandraPersistenceStartupValidator.validate(kit.system.toClassic, 15.seconds), 20.seconds)

      assertEquals(result, Right(()))
    finally kit.shutdownTestKit()
  }

  test("actor system one persists payment state and actor system two recovers it exactly") {
    val payment = samplePayment()
    val commands = capturedCommands(payment, authOperation = "auth-recover", captureOperation = "capture-recover")
    val kitOne = actorTestKit()
    val stateBeforeShutdown =
      try
        val entity = spawn(kitOne, payment.paymentId)
        executeFlow(kitOne, entity, commands)
      finally kitOne.shutdownTestKit()

    val kitTwo = actorTestKit()
    try
      val recovered = spawn(kitTwo, payment.paymentId)

      execute(kitTwo, recovered, createCommand(payment)) match
        case rejected: PaymentEntity.Rejected =>
          assertEquals(rejected.error, PaymentError.PaymentAlreadyCreated)
          assertEquals(rejected.state, stateBeforeShutdown)
        case other => fail(s"Expected duplicate create rejection after recovery, got $other")
    finally kitTwo.shutdownTestKit()
  }

  test("Cassandra journal rows use stable serializer id and manifest with monotonic sequence numbers") {
    val payment = samplePayment()
    val commands = capturedCommands(payment, authOperation = "auth-rows", captureOperation = "capture-rows")
    val kit = actorTestKit()
    try
      val entity = spawn(kit, payment.paymentId)

      assertEquals(executeFlow(kit, entity, commands).productPrefix, "Captured")

      val rows = journalRows(payment.paymentId)
      assertEquals(rows.map(_.getLong("sequence_nr")), (1L to commands.size.toLong).toList)
      assert(rows.forall(_.getInt("ser_id") == PaymentEventSerializer.Identifier))
      assert(rows.forall(_.getString("ser_manifest") == "payment-event-v1"))
    finally kit.shutdownTestKit()
  }

  test("capture pending recovery preserves duplicate and mismatch semantics without new events") {
    val payment = samplePayment()
    val pendingCommands =
      authorizedCommands(payment, "auth-pending") :+
        PaymentCommand.CapturePayment(operation("capture-pending"), now)
    val kitOne = actorTestKit()
    val pendingState =
      try
        val entity = spawn(kitOne, payment.paymentId)
        executeFlow(kitOne, entity, pendingCommands)
      finally kitOne.shutdownTestKit()
    val rowCountAfterPersist = journalRows(payment.paymentId).size

    val kitTwo = actorTestKit()
    try
      val recovered = spawn(kitTwo, payment.paymentId)

      assertEquals(
        execute(
          kitTwo,
          recovered,
          PaymentCommand.CapturePayment(operation("capture-pending"), now)
        ),
        PaymentEntity.DuplicateAccepted(pendingState)
      )
      assertEquals(journalRows(payment.paymentId).size, rowCountAfterPersist)

      assertEquals(
        execute(
          kitTwo,
          recovered,
          PaymentCommand.CapturePayment(operation("capture-different"), now)
        ),
        PaymentEntity.Rejected(
          PaymentError.OperationMismatch(operation("capture-pending"), operation("capture-different")),
          pendingState
        )
      )
      assertEquals(journalRows(payment.paymentId).size, rowCountAfterPersist)
    finally kitTwo.shutdownTestKit()
  }

  test("capture unknown recovery preserves the exact provider operation identity") {
    val payment = samplePayment()
    val unknownOperation = operation("capture-unknown-recovered")
    val commands =
      authorizedCommands(payment, "auth-unknown") ++ List(
        PaymentCommand.CapturePayment(unknownOperation, now),
        PaymentCommand.RecordCaptureUnknown(unknownOperation, now)
      )
    val kitOne = actorTestKit()
    val stateBeforeShutdown =
      try
        val entity = spawn(kitOne, payment.paymentId)
        executeFlow(kitOne, entity, commands)
      finally kitOne.shutdownTestKit()

    val kitTwo = actorTestKit()
    try
      val recovered = spawn(kitTwo, payment.paymentId)

      execute(kitTwo, recovered, createCommand(payment)) match
        case rejected: PaymentEntity.Rejected =>
          assertEquals(rejected.state, stateBeforeShutdown)
          rejected.state match
            case PaymentState.CaptureUnknown(_, _, operationId) =>
              assertEquals(operationId, unknownOperation)
            case other => fail(s"Expected CaptureUnknown, got ${other.productPrefix}")
        case other => fail(s"Expected duplicate create rejection after recovery, got $other")
    finally kitTwo.shutdownTestKit()
  }

  test("payment aggregates remain isolated in the shared Cassandra journal") {
    val paymentA = samplePayment()
    val paymentB = samplePayment()
    val kitOne = actorTestKit()
    val stateA =
      try
        val entityA = spawn(kitOne, paymentA.paymentId)
        val entityB = spawn(kitOne, paymentB.paymentId)
        val createdA = executeFlow(kitOne, entityA, List(createCommand(paymentA)))
        assertEquals(
          executeFlow(
            kitOne,
            entityB,
            capturedCommands(paymentB, authOperation = "auth-b", captureOperation = "capture-b")
          ).productPrefix,
          "Captured"
        )
        createdA
      finally kitOne.shutdownTestKit()

    val kitTwo = actorTestKit()
    try
      val recoveredA = spawn(kitTwo, paymentA.paymentId)
      val recoveredB = spawn(kitTwo, paymentB.paymentId)

      assertEquals(
        execute(kitTwo, recoveredA, createCommand(paymentA)).state,
        stateA
      )
      assertEquals(
        execute(kitTwo, recoveredB, createCommand(paymentB)).state.productPrefix,
        "Captured"
      )
    finally kitTwo.shutdownTestKit()
  }

  test("startup validation fails closed when Cassandra is unavailable") {
    val kit = actorTestKit(unavailableCassandraConfig())
    try
      given ExecutionContext = kit.system.executionContext

      val result =
        Await.result(CassandraPersistenceStartupValidator.validate(kit.system.toClassic, 2.seconds), 8.seconds)

      assert(result.left.exists {
        case _: CassandraPersistenceStartupError.CassandraUnavailable => true
        case _ => false
      })
      assertEquals(
        kit.system.settings.config.getString("pekko.persistence.journal.plugin"),
        "pekko.persistence.cassandra.journal"
      )
    finally kit.shutdownTestKit()
  }

  test("startup validation reports missing keyspaces and does not autocreate schema") {
    val missingKeyspace = uniqueKeyspace("missing")
    val kit = actorTestKit(cassandraJournalConfig(missingKeyspace))
    try
      given ExecutionContext = kit.system.executionContext

      val result =
        Await.result(CassandraPersistenceStartupValidator.validate(kit.system.toClassic, 10.seconds), 15.seconds)

      assertEquals(result.left.map(_.message), Left(s"Cassandra keyspace '$missingKeyspace' is missing"))
      assert(!CassandraJournalSchema.tableNames(cqlSession, missingKeyspace).contains("messages"))
    finally kit.shutdownTestKit()
  }

  test("startup validation reports missing journal tables and leaves autocreate disabled") {
    val keyspace = uniqueKeyspace("tables")
    cqlSession.execute(
      s"CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = {'class': 'NetworkTopologyStrategy', '${cassandra.getLocalDatacenter}': 1}"
    )
    val kit = actorTestKit(cassandraJournalConfig(keyspace))
    try
      given ExecutionContext = kit.system.executionContext

      val result =
        Await.result(CassandraPersistenceStartupValidator.validate(kit.system.toClassic, 10.seconds), 15.seconds)

      assert(result.left.exists(_.isInstanceOf[CassandraPersistenceStartupError.MissingJournalTable]))
      assertEquals(CassandraJournalSchema.tableNames(cqlSession, keyspace), Set.empty)
    finally kit.shutdownTestKit()
  }

  test("journal write failure produces no false Accepted reply") {
    val keyspace = uniqueKeyspace("writefail")
    cqlSession.execute(
      s"CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = {'class': 'NetworkTopologyStrategy', '${cassandra.getLocalDatacenter}': 1}"
    )
    val payment = samplePayment()
    val kit = actorTestKit(cassandraJournalConfig(keyspace))
    try
      val entity = spawn(kit, payment.paymentId)
      val replyProbe = kit.createTestProbe[PaymentEntity.Reply]()
      val deathProbe = kit.createTestProbe[Nothing]()

      entity ! PaymentEntity.Execute(createCommand(payment), replyProbe.ref)

      replyProbe.expectNoMessage(1.second)
      deathProbe.expectTerminated(entity, 15.seconds)
      assertEquals(CassandraJournalSchema.tableNames(cqlSession, keyspace), Set.empty)
    finally kit.shutdownTestKit()
  }

  private def actorTestKit(config: Config = cassandraJournalConfig()): ActorTestKit =
    ActorTestKit(s"PaymentEntityCassandraPersistenceSuite-${UUID.randomUUID()}", config)

  private def spawn(
      kit: ActorTestKit,
      paymentId: PaymentId
  ): ActorRef[PaymentEntity.Command] =
    kit.spawn(PaymentEntity(paymentId), s"payment-${UUID.randomUUID()}")

  private def execute(
      kit: ActorTestKit,
      entity: ActorRef[PaymentEntity.Command],
      command: PaymentCommand
  ): PaymentEntity.Reply =
    val probe = kit.createTestProbe[PaymentEntity.Reply]()
    entity ! PaymentEntity.Execute(command, probe.ref)
    probe.receiveMessage(10.seconds)

  private def executeFlow(
      kit: ActorTestKit,
      entity: ActorRef[PaymentEntity.Command],
      commands: List[PaymentCommand]
  ): PaymentState =
    commands.foldLeft(PaymentState.NotCreated) { case (_, command) =>
      execute(kit, entity, command) match
        case accepted: PaymentEntity.Accepted => accepted.state
        case duplicate: PaymentEntity.DuplicateAccepted => duplicate.state
        case rejected: PaymentEntity.Rejected => fail(s"Unexpected rejection: ${rejected.error}")
        case invalid: PaymentEntity.InvalidEnvelope => fail(s"Unexpected invalid envelope: ${invalid.reason}")
    }

  private def journalRows(paymentId: PaymentId): List[Row] =
    cqlSession
      .execute(
        s"SELECT persistence_id, sequence_nr, ser_id, ser_manifest FROM ${CassandraJournalSchema.Keyspace}.messages"
      )
      .all()
      .asScala
      .filter(_.getString("persistence_id") == PaymentEntity.persistenceId(paymentId))
      .toList
      .sortBy(_.getLong("sequence_nr"))

  private def cassandraJournalConfig(
      keyspace: String = CassandraJournalSchema.Keyspace
  ): Config =
    ConfigFactory
      .parseString(s"""
        pekko.actor {
          allow-java-serialization = off
          serializers.payment-event = "${classOf[PaymentEventSerializer].getName}"
          serialization-bindings."com.paymentprocessing.domain.payment.PaymentEvent" = payment-event
        }

        pekko.persistence {
          journal.plugin = "pekko.persistence.cassandra.journal"

          cassandra {
            coordinated-shutdown-on-error = on

            journal {
              keyspace = "$keyspace"
              keyspace-autocreate = false
              tables-autocreate = false
              target-partition-size = 500000
              support-deletes = off
            }
          }
        }

        datastax-java-driver {
          basic.contact-points = ["${cassandra.getHost}:${cassandra.getMappedPort(9042)}"]
          basic.load-balancing-policy.local-datacenter = "${cassandra.getLocalDatacenter}"
          advanced.reconnect-on-init = true

          profiles.pekko-persistence-cassandra-profile.basic.request {
            consistency = QUORUM
            default-idempotence = true
            timeout = 5 seconds
          }
        }
      """)
      .withFallback(ConfigFactory.load())
      .resolve()

  private def unavailableCassandraConfig(): Config =
    ConfigFactory
      .parseString(s"""
        pekko.actor {
          allow-java-serialization = off
          serializers.payment-event = "${classOf[PaymentEventSerializer].getName}"
          serialization-bindings."com.paymentprocessing.domain.payment.PaymentEvent" = payment-event
        }

        pekko.persistence {
          journal.plugin = "pekko.persistence.cassandra.journal"

          cassandra {
            coordinated-shutdown-on-error = on

            journal {
              keyspace = "${CassandraJournalSchema.Keyspace}"
              keyspace-autocreate = false
              tables-autocreate = false
              target-partition-size = 500000
              support-deletes = off
            }
          }
        }

        datastax-java-driver {
          basic.contact-points = ["127.0.0.1:1"]
          basic.load-balancing-policy.local-datacenter = "${cassandra.getLocalDatacenter}"
          advanced.reconnect-on-init = true

          profiles.pekko-persistence-cassandra-profile.basic.request {
            consistency = QUORUM
            default-idempotence = true
            timeout = 500 millis
          }
        }
      """)
      .withFallback(ConfigFactory.load())
      .resolve()

  private def capturedCommands(
      payment: Payment,
      authOperation: String,
      captureOperation: String
  ): List[PaymentCommand] =
    authorizedCommands(payment, authOperation) ++ List(
      PaymentCommand.CapturePayment(operation(captureOperation), now),
      PaymentCommand.RecordCaptureSucceeded(operation(captureOperation), now)
    )

  private def authorizedCommands(payment: Payment, authOperation: String): List[PaymentCommand] =
    List(
      createCommand(payment),
      PaymentCommand.StartFraudCheck(now),
      PaymentCommand.RecordFraudApproved(now),
      PaymentCommand.AuthorizePayment(operation(authOperation), now),
      PaymentCommand.RecordAuthorizationSucceeded(operation(authOperation), now)
    )

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

  private def samplePayment(): Payment =
    Payment(
      PaymentId.from(UUID.randomUUID()),
      TenantId.from(UUID.randomUUID()),
      CustomerId.from(UUID.randomUUID()),
      MerchantId.from(UUID.randomUUID()),
      money("100.00"),
      PaymentMethodToken.from(s"tok_${UUID.randomUUID()}").fold(error => fail(error.toString), identity),
      now
    )

  private def operation(value: String): ProviderOperationId =
    ProviderOperationId.from(value).fold(error => fail(error.toString), identity)

  private def money(value: String): Money =
    Money.from(BigDecimal(value), Currency.PLN).fold(error => fail(error.toString), identity)

  private def uniqueKeyspace(prefix: String): String =
    s"p4_${prefix.take(8)}_${UUID.randomUUID().toString.replace("-", "").take(16)}"
