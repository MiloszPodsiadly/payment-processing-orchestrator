package com.paymentprocessing.adapter.cassandra

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.cassandra.CassandraSessionSettings
import org.apache.pekko.stream.connectors.cassandra.javadsl.CassandraSession
import org.apache.pekko.stream.connectors.cassandra.javadsl.CassandraSessionRegistry

import java.util.Optional
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters._
import scala.util.control.NonFatal

object CassandraPersistenceStartupValidator:
  def validate(
      system: ActorSystem,
      timeout: FiniteDuration
  )(using ExecutionContext): Future[Either[CassandraPersistenceStartupError, Unit]] =
    validateConfiguration(system.settings.config) match
      case Left(error) => Future.successful(Left(error))
      case Right(configuration) =>
        val session =
          CassandraSessionRegistry
            .get(system)
            .sessionFor(CassandraSessionSettings.create("pekko.persistence.cassandra"))

        withTimeout(
          validateSchema(session, configuration).map(Right(_)).recover {
            case ValidationFailure(error) => Left(error)
            case NonFatal(error) =>
              Left(CassandraPersistenceStartupError.CassandraUnavailable(error.getMessage))
          },
          timeout,
          system
        )

  def validateConfiguration(
      config: Config
  ): Either[CassandraPersistenceStartupError, CassandraPersistenceConfiguration] =
    try
      val resolved = config.resolve()
      val journalPlugin = requiredString(resolved, "pekko.persistence.journal.plugin")
      val keyspace = requiredString(resolved, "pekko.persistence.cassandra.journal.keyspace")
      val contactPoints = requiredStringList(resolved, "datastax-java-driver.basic.contact-points")
      val localDatacenter =
        requiredString(
          resolved,
          "datastax-java-driver.basic.load-balancing-policy.local-datacenter"
        )

      for
        journal <- journalPlugin
        _ <- requireExact(
          "pekko.persistence.journal.plugin",
          journal,
          "pekko.persistence.cassandra.journal"
        )
        journalKeyspace <- keyspace
        _ <- requireExact(
          "pekko.persistence.cassandra.journal.keyspace",
          journalKeyspace,
          CassandraJournalContract.CanonicalKeyspace
        )
        points <- contactPoints
        _ <- requireContactPoints(points)
        datacenter <- localDatacenter
        _ <- requireFalse(resolved, "pekko.persistence.cassandra.journal.keyspace-autocreate")
        _ <- requireFalse(resolved, "pekko.persistence.cassandra.journal.tables-autocreate")
        _ <- requireFalse(resolved, "pekko.persistence.cassandra.journal.support-deletes")
        _ <- requireExactInt(
          resolved,
          "pekko.persistence.cassandra.journal.target-partition-size",
          CassandraJournalContract.TargetPartitionSize
        )
        _ <- requireTrue(resolved, "datastax-java-driver.advanced.reconnect-on-init")
      yield CassandraPersistenceConfiguration(
        keyspace = journalKeyspace,
        contactPoints = points,
        localDatacenter = datacenter
      )
    catch
      case error: ConfigException =>
        Left(
          CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
            error.getMessage
          )
        )

  private def validateSchema(
      session: CassandraSession,
      configuration: CassandraPersistenceConfiguration
  )(using ExecutionContext): Future[Unit] =
    for
      _ <- requireLocalDatacenter(session, configuration.localDatacenter)
      _ <- requireKeyspace(session, configuration.keyspace)
      _ <- requireTables(session, configuration.keyspace)
      _ <- requireStructuralSchema(session, configuration.keyspace)
    yield ()

  private def requireLocalDatacenter(
      session: CassandraSession,
      expectedLocalDatacenter: String
  )(using ExecutionContext): Future[Unit] =
    one(session, "SELECT data_center FROM system.local").map { row =>
      val actual = row.map(_.getString("data_center")).getOrElse("")
      if actual != expectedLocalDatacenter then
        throw ValidationFailure(
          CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
            s"Configured Cassandra local datacenter '$expectedLocalDatacenter' does not match system.local data_center '$actual'"
          )
        )
    }

  private def requireKeyspace(
      session: CassandraSession,
      keyspace: String
  )(using ExecutionContext): Future[Unit] =
    one(
      session,
      "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?",
      keyspace
    ).map { row =>
      if row.isEmpty then
        throw ValidationFailure(CassandraPersistenceStartupError.MissingKeyspace(keyspace))
    }

  private def requireTables(
      session: CassandraSession,
      keyspace: String
  )(using ExecutionContext): Future[Unit] =
    Future
      .traverse(CassandraJournalSchema.RequiredTables.toSeq.sorted) { table =>
        one(
          session,
          "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ? AND table_name = ?",
          keyspace,
          table
        ).map(row => table -> row.nonEmpty)
      }
      .map { tableResults =>
        tableResults.collectFirst { case (table, false) => table }.foreach { table =>
          throw ValidationFailure(
            CassandraPersistenceStartupError.MissingJournalTable(keyspace, table)
          )
        }
      }

  private def requireStructuralSchema(
      session: CassandraSession,
      keyspace: String
  )(using ExecutionContext): Future[Unit] =
    Future
      .traverse(CassandraJournalSchema.RequiredTablesSchema.values.toSeq.sortBy(_.tableName)) {
        expectedTable =>
          many(
            session,
            "SELECT column_name, kind, position, type FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
            keyspace,
            expectedTable.tableName
          ).map { rows =>
            val actualByName =
              rows
                .map(row =>
                  ActualColumn(
                    name = row.getString("column_name"),
                    cqlType = normalizeCqlType(row.getString("type")),
                    kind = row.getString("kind"),
                    position = row.getInt("position")
                  )
                )
                .map(column => column.name -> column)
                .toMap

            val details =
              structuralDifferences(expectedTable, actualByName)

            if details.nonEmpty then
              throw ValidationFailure(
                CassandraPersistenceStartupError.IncompatibleJournalSchema(
                  keyspace,
                  expectedTable.tableName,
                  details.mkString("; ")
                )
              )
          }
      }
      .map(_ => ())

  private def one(
      session: CassandraSession,
      cql: String,
      bindValues: AnyRef*
  )(using ExecutionContext): Future[Option[com.datastax.oss.driver.api.core.cql.Row]] =
    session.selectOne(cql, bindValues*).asScala.map(optionalToOption)

  private def many(
      session: CassandraSession,
      cql: String,
      bindValues: AnyRef*
  )(using ExecutionContext): Future[List[com.datastax.oss.driver.api.core.cql.Row]] =
    session.selectAll(cql, bindValues*).asScala.map(_.asScala.toList)

  private def optionalToOption[A](optional: Optional[A]): Option[A] =
    optional.toScala

  private def requiredString(
      config: Config,
      path: String
  ): Either[CassandraPersistenceStartupError, String] =
    if !config.hasPath(path) then
      Left(
        CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
          s"$path must be defined"
        )
      )
    else
      val value = config.getString(path).trim
      if value.isEmpty then
        Left(
          CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
            s"$path must be non-blank"
          )
        )
      else Right(value)

  private def requiredStringList(
      config: Config,
      path: String
  ): Either[CassandraPersistenceStartupError, List[String]] =
    if !config.hasPath(path) then
      Left(
        CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
          s"$path must be defined"
        )
      )
    else
      val values = config.getStringList(path).asScala.toList.map(_.trim)
      if values.isEmpty || values.exists(_.isEmpty) then
        Left(
          CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
            s"$path must be non-empty"
          )
        )
      else Right(values)

  private def requireContactPoints(
      contactPoints: List[String]
  ): Either[CassandraPersistenceStartupError, Unit] =
    val invalid = contactPoints.filterNot { point =>
      val parts = point.split(":", 2).toList
      parts match
        case host :: port :: Nil =>
          host.nonEmpty && port.toIntOption.exists(value => value >= 1 && value <= 65535)
        case _ => false
    }

    Either.cond(
      invalid.isEmpty,
      (),
      CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
        s"datastax-java-driver.basic.contact-points contains invalid entries: ${invalid.mkString(", ")}"
      )
    )

  private def requireExact(
      path: String,
      actual: String,
      expected: String
  ): Either[CassandraPersistenceStartupError, Unit] =
    Either.cond(
      actual == expected,
      (),
      CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
        s"$path must be '$expected', got '$actual'"
      )
    )

  private def requireFalse(
      config: Config,
      path: String
  ): Either[CassandraPersistenceStartupError, Unit] =
    Either.cond(
      config.hasPath(path) && !config.getBoolean(path),
      (),
      CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
        s"$path must be false"
      )
    )

  private def requireTrue(
      config: Config,
      path: String
  ): Either[CassandraPersistenceStartupError, Unit] =
    Either.cond(
      config.hasPath(path) && config.getBoolean(path),
      (),
      CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
        s"$path must be true"
      )
    )

  private def requireExactInt(
      config: Config,
      path: String,
      expected: Int
  ): Either[CassandraPersistenceStartupError, Unit] =
    Either.cond(
      config.hasPath(path) && config.getInt(path) == expected,
      (),
      CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
        s"$path must be $expected"
      )
    )

  private def structuralDifferences(
      expectedTable: TableSchema,
      actualByName: Map[String, ActualColumn]
  ): List[String] =
    val expectedByName = expectedTable.columns.map(column => column.name -> column).toMap

    val missing =
      expectedTable.columns.toList
        .filterNot(column => actualByName.contains(column.name))
        .sortBy(_.name)
        .map(column => s"missing column ${column.name}")

    val mismatched =
      expectedTable.columns.toList
        .sortBy(_.name)
        .flatMap { expected =>
          actualByName.get(expected.name).toList.flatMap { actual =>
            val expectedType = normalizeCqlType(expected.cqlType)
            val expectedKind = expected.kind.systemSchemaValue
            List(
              Option.when(actual.cqlType != expectedType)(
                s"${expected.name} type expected $expectedType got ${actual.cqlType}"
              ),
              Option.when(actual.kind != expectedKind)(
                s"${expected.name} kind expected $expectedKind got ${actual.kind}"
              ),
              Option.when(actual.position != expected.position)(
                s"${expected.name} position expected ${expected.position} got ${actual.position}"
              )
            ).flatten
          }
        }

    val unsafeExtras =
      actualByName.values.toList
        .filter(column =>
          !expectedByName.contains(
            column.name
          ) && column.kind != ColumnKind.Regular.systemSchemaValue
        )
        .sortBy(_.name)
        .map(column => s"unexpected key column ${column.name} (${column.kind})")

    missing ++ mismatched ++ unsafeExtras

  private def normalizeCqlType(value: String): String =
    value.toLowerCase.replace(" ", "")

  private def withTimeout(
      validation: Future[Either[CassandraPersistenceStartupError, Unit]],
      timeout: FiniteDuration,
      system: ActorSystem
  )(using ExecutionContext): Future[Either[CassandraPersistenceStartupError, Unit]] =
    val promise = Promise[Either[CassandraPersistenceStartupError, Unit]]()
    val cancellable =
      system.scheduler.scheduleOnce(timeout) {
        (promise.trySuccess(
          Left(
            CassandraPersistenceStartupError.CassandraUnavailable(
              s"Startup validation timed out after $timeout"
            )
          )
        ): Unit)
      }(using system.dispatcher)

    validation.onComplete { result =>
      (cancellable.cancel(): Unit)
      (promise.tryComplete(result): Unit)
    }
    promise.future

final case class CassandraPersistenceConfiguration(
    keyspace: String,
    contactPoints: List[String],
    localDatacenter: String
)

sealed trait CassandraPersistenceStartupError:
  def message: String

object CassandraPersistenceStartupError:
  final case class CassandraUnavailable(details: String) extends CassandraPersistenceStartupError:
    override def message: String = s"Cassandra unavailable: $details"

  final case class MissingKeyspace(keyspace: String) extends CassandraPersistenceStartupError:
    override def message: String = s"Cassandra keyspace '$keyspace' is missing"

  final case class MissingJournalTable(keyspace: String, table: String)
      extends CassandraPersistenceStartupError:
    override def message: String = s"Cassandra journal table '$keyspace.$table' is missing"

  final case class IncompatibleJournalSchema(keyspace: String, table: String, details: String)
      extends CassandraPersistenceStartupError:
    override def message: String =
      s"Cassandra journal table '$keyspace.$table' has incompatible schema: $details"

  final case class InvalidCassandraPersistenceConfiguration(details: String)
      extends CassandraPersistenceStartupError:
    override def message: String = s"Invalid Cassandra persistence configuration: $details"

private final case class ActualColumn(
    name: String,
    cqlType: String,
    kind: String,
    position: Int
)

private final case class ValidationFailure(error: CassandraPersistenceStartupError)
    extends RuntimeException(error.message)
