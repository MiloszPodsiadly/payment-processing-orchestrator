package com.paymentprocessing.persistence.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.paymentprocessing.adapter.cassandra.CassandraJournalContract
import com.paymentprocessing.adapter.cassandra.CassandraJournalSchema
import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupError
import com.paymentprocessing.bootstrap.config.ProductionRuntimeConfig
import com.paymentprocessing.bootstrap.runtime.PaymentRuntime
import com.paymentprocessing.bootstrap.runtime.PaymentRuntimeStartupError
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
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import org.testcontainers.cassandra.CassandraContainer

import java.time.Instant
import java.util.UUID
import scala.compiletime.uninitialized
import scala.concurrent.Await
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
    CassandraMigrationTestSupport.applyMigration(cqlSession)

  override def afterAll(): Unit =
    if cqlSession != null then cqlSession.close()
    if cassandra != null then cassandra.stop()

  test("migration creates the required Pekko Cassandra journal schema and is idempotent") {
    CassandraMigrationTestSupport.applyMigration(cqlSession)

    assert(CassandraMigrationTestSupport.tableNames(cqlSession).contains("messages"))
    assertEquals(
      CassandraJournalSchema.RequiredTables.diff(CassandraMigrationTestSupport.tableNames(cqlSession)),
      Set.empty
    )
    assertEquals(
      CassandraJournalSchema.RequiredMessagesColumns.diff(
        CassandraMigrationTestSupport.messagesColumns(cqlSession)
      ),
      Set.empty
    )
  }

  test("production runtime returns ready only after prepared Cassandra journal validation succeeds") {
    val started =
      Await.result(PaymentRuntime.start(testContainerOverrides(), 15.seconds), 25.seconds)

    started match
      case Right(runtime) =>
        try
          assertEquals(runtime.appConfig.cassandra.localDatacenter, cassandra.getLocalDatacenter)
          assertEquals(
            runtime.system.settings.config.getString("pekko.persistence.cassandra.journal.keyspace"),
            CassandraJournalContract.CanonicalKeyspace
          )
        finally
          runtime.system.terminate()
          val _ = Await.result(runtime.system.whenTerminated, 10.seconds)
      case Left(error) =>
        fail(s"Expected production runtime readiness, got ${error.message}")
  }

  test("PaymentRuntime restart recovers durable PaymentEntity state from Cassandra") {
    val payment = samplePayment()
    val commands =
      capturedCommands(payment, authOperation = "auth-runtime", captureOperation = "capture-runtime")
    val runtimeOne = startRuntime()
    val stateBeforeShutdown =
      try
        val entity = spawn(runtimeOne, payment.paymentId)
        executeFlow(runtimeOne, entity, commands)
      finally stopRuntime(runtimeOne)
    val rowCountAfterShutdown = journalRows(payment.paymentId).size

    val runtimeTwo = startRuntime()
    try
      val recovered = spawn(runtimeTwo, payment.paymentId)

      execute(runtimeTwo, recovered, createCommand(payment)) match
        case rejected: PaymentEntity.Rejected =>
          assertEquals(rejected.error, PaymentError.PaymentAlreadyCreated)
          assertEquals(rejected.state, stateBeforeShutdown)
        case other => fail(s"Expected duplicate create rejection after runtime restart, got $other")
      assertEquals(journalRows(payment.paymentId).size, rowCountAfterShutdown)
    finally stopRuntime(runtimeTwo)
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
    val result =
      Await.result(PaymentRuntime.start(unavailableCassandraOverrides(), 2.seconds), 10.seconds)

    assert(cassandraError(result) match
      case _: CassandraPersistenceStartupError.CassandraUnavailable => true
      case _ => false
    )
  }

  test("production runtime reports missing canonical keyspace and does not autocreate schema") {
    withAlteredSharedSchema { session =>
      val _ = session.execute(s"DROP KEYSPACE IF EXISTS ${CassandraJournalContract.CanonicalKeyspace}")
      val result =
        Await.result(PaymentRuntime.start(testContainerOverrides(), 10.seconds), 20.seconds)

      assertEquals(
        cassandraError(result).message,
        s"Cassandra keyspace '${CassandraJournalContract.CanonicalKeyspace}' is missing"
      )
      assert(
        !CassandraMigrationTestSupport
          .tableNames(session, CassandraJournalContract.CanonicalKeyspace)
          .contains("messages")
      )
    }
  }

  test("production runtime reports missing canonical journal tables and leaves autocreate disabled") {
    withAlteredSharedSchema { session =>
      val _ = session.execute(s"DROP KEYSPACE IF EXISTS ${CassandraJournalContract.CanonicalKeyspace}")
      createCanonicalKeyspace(session, cassandra)

      val result =
        Await.result(PaymentRuntime.start(testContainerOverrides(), 10.seconds), 20.seconds)

      assert(cassandraError(result).isInstanceOf[CassandraPersistenceStartupError.MissingJournalTable])
      assertEquals(
        CassandraMigrationTestSupport.tableNames(
          session,
          CassandraJournalContract.CanonicalKeyspace
        ),
        Set.empty
      )
    }
  }

  test("production runtime rejects structurally incompatible canonical journal schema") {
    withMalformedSharedSchema { session =>
      val _ = session.execute("ALTER TABLE pekko.messages DROP tags")
      val result =
        Await.result(PaymentRuntime.start(testContainerOverrides(), 10.seconds), 20.seconds)

      assert(cassandraError(result) match
        case error: CassandraPersistenceStartupError.IncompatibleJournalSchema =>
          error.table == "messages" && error.details.contains("missing column tags")
        case _ => false
      )
    }
  }

  test("production runtime rejects messages schema with wrong event CQL type") {
    withMalformedSharedSchema { session =>
      replaceMessagesTable(session)(_.replace("event blob,", "event text,"))
      val result =
        Await.result(PaymentRuntime.start(testContainerOverrides(), 10.seconds), 20.seconds)

      cassandraError(result) match
        case error: CassandraPersistenceStartupError.IncompatibleJournalSchema =>
          assertEquals(error.table, "messages")
          assert(error.details.contains("event type expected blob got text"))
        case other => fail(s"Expected incompatible messages schema, got ${other.message}")
    }
  }

  test("production runtime rejects messages schema with wrong partition key shape") {
    withMalformedSharedSchema { session =>
      replaceMessagesTable(session)(
        _.replace(
          "PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp))",
          "PRIMARY KEY (persistence_id, partition_nr, sequence_nr, timestamp))"
        )
      )
      val result =
        Await.result(PaymentRuntime.start(testContainerOverrides(), 10.seconds), 20.seconds)

      cassandraError(result) match
        case error: CassandraPersistenceStartupError.IncompatibleJournalSchema =>
          assertEquals(error.table, "messages")
          assert(error.details.contains("partition_nr kind expected partition_key got clustering"))
        case other => fail(s"Expected incompatible messages schema, got ${other.message}")
    }
  }

  test("production runtime rejects messages schema with wrong clustering key order") {
    withMalformedSharedSchema { session =>
      replaceMessagesTable(session) { statement =>
        statement
          .replace(
            "PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp))",
            "PRIMARY KEY ((persistence_id, partition_nr), timestamp, sequence_nr))"
          )
          .replace(
            "WITH gc_grace_seconds = 864000",
            "WITH CLUSTERING ORDER BY (timestamp ASC, sequence_nr ASC)\n  AND gc_grace_seconds = 864000"
          )
      }
      val result =
        Await.result(PaymentRuntime.start(testContainerOverrides(), 10.seconds), 20.seconds)

      cassandraError(result) match
        case error: CassandraPersistenceStartupError.IncompatibleJournalSchema =>
          assertEquals(error.table, "messages")
          assert(error.details.contains("sequence_nr position expected 0 got 1"))
          assert(error.details.contains("timestamp position expected 1 got 0"))
        case other => fail(s"Expected incompatible messages schema, got ${other.message}")
    }
  }

  test("journal write failure produces no false Accepted reply") {
    withAlteredSharedSchema { session =>
      val _ = session.execute(s"DROP KEYSPACE IF EXISTS ${CassandraJournalContract.CanonicalKeyspace}")
      createCanonicalKeyspace(session, cassandra)
      val payment = samplePayment()
      val kit = actorTestKit(productionCassandraConfig())
      try
        val entity = spawn(kit, payment.paymentId)
        val replyProbe = kit.createTestProbe[PaymentEntity.Reply]()
        val deathProbe = kit.createTestProbe[Nothing]()

        entity ! PaymentEntity.Execute(createCommand(payment), replyProbe.ref)

        replyProbe.expectNoMessage(1.second)
        deathProbe.expectTerminated(entity, 15.seconds)
        assertEquals(
          CassandraMigrationTestSupport.tableNames(
            session,
            CassandraJournalContract.CanonicalKeyspace
          ),
          Set.empty
        )
      finally kit.shutdownTestKit()
    }
  }

  test("Cassandra outage after successful write produces no false Accepted and preserves journal") {
    val payment = samplePayment()
    val kit = actorTestKit()
    val successfulState =
      try
        val entity = spawn(kit, payment.paymentId)
        executeFlow(kit, entity, authorizedCommands(payment, "auth-before-outage"))
      finally kit.shutdownTestKit()

    val rowCountBeforeOutage = journalRows(payment.paymentId).size

    val outageKit = actorTestKit()
    try
      val entity = spawn(outageKit, payment.paymentId)
      val replyProbe = outageKit.createTestProbe[PaymentEntity.Reply]()
      val deathProbe = outageKit.createTestProbe[Nothing]()

      execute(outageKit, entity, createCommand(payment)) match
        case rejected: PaymentEntity.Rejected =>
          assertEquals(rejected.error, PaymentError.PaymentAlreadyCreated)
          assertEquals(rejected.state, successfulState)
        case other => fail(s"Expected confirmed recovery before outage, got $other")
      assertEquals(journalRows(payment.paymentId).size, rowCountBeforeOutage)

      setCassandraBinaryTransport(enabled = false)
      try
        entity ! PaymentEntity.Execute(
          PaymentCommand.CapturePayment(operation("capture-during-outage"), now),
          replyProbe.ref
        )

        replyProbe.expectNoMessage(2.seconds)
        deathProbe.expectTerminated(entity, 20.seconds)
      finally setCassandraBinaryTransport(enabled = true)
    finally outageKit.shutdownTestKit()

    val recoveryKit = actorTestKit()
    try
      assertEquals(journalRows(cassandra, payment.paymentId).size, rowCountBeforeOutage)
      val recovered = spawn(recoveryKit, payment.paymentId)

      execute(recoveryKit, recovered, createCommand(payment)) match
        case rejected: PaymentEntity.Rejected =>
          assertEquals(rejected.error, PaymentError.PaymentAlreadyCreated)
          assertEquals(rejected.state, successfulState)
        case other => fail(s"Expected duplicate create rejection after outage recovery, got $other")
      assertEquals(journalRows(cassandra, payment.paymentId).size, rowCountBeforeOutage)
    finally recoveryKit.shutdownTestKit()
  }

  private def startRuntime(container: CassandraContainer = cassandra): PaymentRuntime.ReadyRuntime =
    Await.result(PaymentRuntime.start(testContainerOverrides(container), 15.seconds), 25.seconds) match
      case Right(runtime) => runtime
      case Left(error) => fail(s"Expected ready runtime, got ${error.message}")

  private def stopRuntime(runtime: PaymentRuntime.ReadyRuntime): Unit =
    runtime.system.terminate()
    val _ = Await.result(runtime.system.whenTerminated, 10.seconds)

  private def actorTestKit(config: Config = productionCassandraConfig()): ActorTestKit =
    ActorTestKit(s"PaymentEntityCassandraPersistenceSuite-${UUID.randomUUID()}", config)

  private def spawn(
      kit: ActorTestKit,
      paymentId: PaymentId
  ): ActorRef[PaymentEntity.Command] =
    kit.spawn(PaymentEntity(paymentId), s"payment-${UUID.randomUUID()}")

  private def spawn(
      runtime: PaymentRuntime.ReadyRuntime,
      paymentId: PaymentId
  ): ActorRef[PaymentEntity.Command] =
    runtime.system.systemActorOf(PaymentEntity(paymentId), s"payment-${UUID.randomUUID()}")

  private def execute(
      kit: ActorTestKit,
      entity: ActorRef[PaymentEntity.Command],
      command: PaymentCommand
  ): PaymentEntity.Reply =
    val probe = kit.createTestProbe[PaymentEntity.Reply]()
    entity ! PaymentEntity.Execute(command, probe.ref)
    probe.receiveMessage(10.seconds)

  private def execute(
      runtime: PaymentRuntime.ReadyRuntime,
      entity: ActorRef[PaymentEntity.Command],
      command: PaymentCommand
  ): PaymentEntity.Reply =
    given Timeout = Timeout(10.seconds)
    given Scheduler = runtime.system.scheduler

    Await.result(
      entity.ask[PaymentEntity.Reply](replyTo => PaymentEntity.Execute(command, replyTo)),
      10.seconds
    )

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

  private def executeFlow(
      runtime: PaymentRuntime.ReadyRuntime,
      entity: ActorRef[PaymentEntity.Command],
      commands: List[PaymentCommand]
  ): PaymentState =
    commands.foldLeft(PaymentState.NotCreated) { case (_, command) =>
      execute(runtime, entity, command) match
        case accepted: PaymentEntity.Accepted => accepted.state
        case duplicate: PaymentEntity.DuplicateAccepted => duplicate.state
        case rejected: PaymentEntity.Rejected => fail(s"Unexpected rejection: ${rejected.error}")
        case invalid: PaymentEntity.InvalidEnvelope => fail(s"Unexpected invalid envelope: ${invalid.reason}")
    }

  private def journalRows(paymentId: PaymentId): List[Row] =
    journalRows(cqlSession, paymentId)

  private def journalRows(container: CassandraContainer, paymentId: PaymentId): List[Row] =
    withCqlSession(container)(journalRows(_, paymentId))

  private def journalRows(session: CqlSession, paymentId: PaymentId): List[Row] =
    List(0L, 1L)
      .flatMap { partition =>
        session
          .execute(
            s"SELECT persistence_id, sequence_nr, ser_id, ser_manifest FROM ${CassandraJournalSchema.Keyspace}.messages WHERE persistence_id = ? AND partition_nr = ?",
            PaymentEntity.persistenceId(paymentId),
            Long.box(partition)
          )
          .all()
          .asScala
          .toList
      }
      .sortBy(_.getLong("sequence_nr"))

  private def productionCassandraConfig(container: CassandraContainer = cassandra): Config =
    ProductionRuntimeConfig
      .load(testContainerOverrides(container))
      .fold(error => fail(error.message), identity)

  private def testContainerOverrides(container: CassandraContainer = cassandra): Config =
    ConfigFactory.parseString(s"""
      payment.application.environment = "test"
      payment.cassandra.host = "${container.getHost}"
      payment.cassandra.port = ${container.getMappedPort(9042)}
      payment.cassandra.local-datacenter = "${container.getLocalDatacenter}"
    """)

  private def unavailableCassandraOverrides(): Config =
    ConfigFactory.parseString("""
      payment.application.environment = "test"
      payment.cassandra.host = "127.0.0.1"
      payment.cassandra.port = 1
      payment.cassandra.local-datacenter = "datacenter1"
    """)

  private def withCqlSession[A](container: CassandraContainer)(run: CqlSession => A): A =
    val session =
      CqlSession
        .builder()
        .addContactPoint(container.getContactPoint)
        .withLocalDatacenter(container.getLocalDatacenter)
        .build()
    try run(session)
    finally session.close()

  private def createCanonicalKeyspace(session: CqlSession, container: CassandraContainer): Unit =
    val _ = session.execute(
      s"CREATE KEYSPACE IF NOT EXISTS ${CassandraJournalContract.CanonicalKeyspace} WITH replication = {'class': 'NetworkTopologyStrategy', '${container.getLocalDatacenter}': 1}"
    )

  private def replaceMessagesTable(session: CqlSession)(transform: String => String): Unit =
    val canonicalMessagesTable =
      CassandraMigrationTestSupport.migrationStatements
        .find(_.startsWith("CREATE TABLE IF NOT EXISTS pekko.messages"))
        .getOrElse(fail("Missing canonical messages table migration statement"))

    val _ = session.execute("DROP TABLE pekko.messages")
    val _ = session.execute(transform(canonicalMessagesTable))

  private def withMalformedSharedSchema(run: CqlSession => Unit): Unit =
    try
      restoreMigratedSchema()
      run(cqlSession)
    finally restoreMigratedSchema()

  private def withAlteredSharedSchema(run: CqlSession => Unit): Unit =
    try run(cqlSession)
    finally restoreMigratedSchema()

  private def restoreMigratedSchema(): Unit =
    val _ = cqlSession.execute(s"DROP KEYSPACE IF EXISTS ${CassandraJournalContract.CanonicalKeyspace}")
    CassandraMigrationTestSupport.applyMigration(cqlSession)

  private def setCassandraBinaryTransport(enabled: Boolean): Unit =
    val command = if enabled then "enablebinary" else "disablebinary"
    val result = cassandra.execInContainer("nodetool", command)

    assertEquals(
      result.getExitCode,
      0,
      s"nodetool $command failed: ${result.getStderr}"
    )

  private def cassandraError(
      result: Either[PaymentRuntimeStartupError, PaymentRuntime.ReadyRuntime]
  ): CassandraPersistenceStartupError =
    result match
      case Left(PaymentRuntimeStartupError.CassandraPersistenceValidationFailed(error)) => error
      case Left(error) => fail(s"Expected Cassandra validation failure, got ${error.message}")
      case Right(runtime) =>
        try fail("Expected Cassandra validation failure, got ready runtime")
        finally stopRuntime(runtime)

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
