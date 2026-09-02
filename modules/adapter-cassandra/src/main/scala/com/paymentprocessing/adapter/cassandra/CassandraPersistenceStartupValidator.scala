package com.paymentprocessing.adapter.cassandra

import com.typesafe.config.Config
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
    val resolved = config.resolve()
    val journalPlugin = requiredString(resolved, "pekko.persistence.journal.plugin")
    val keyspace = requiredString(resolved, "pekko.persistence.cassandra.journal.keyspace")
    val contactPoints = requiredStringList(resolved, "datastax-java-driver.basic.contact-points")
    val localDatacenter =
      requiredString(resolved, "datastax-java-driver.basic.load-balancing-policy.local-datacenter")

    for
      journal <- journalPlugin
      _ <- requireExact(
        "pekko.persistence.journal.plugin",
        journal,
        "pekko.persistence.cassandra.journal"
      )
      journalKeyspace <- keyspace
      points <- contactPoints
      _ <- requireContactPoints(points)
      datacenter <- localDatacenter
      _ <- requireFalse(resolved, "pekko.persistence.cassandra.journal.keyspace-autocreate")
      _ <- requireFalse(resolved, "pekko.persistence.cassandra.journal.tables-autocreate")
      _ <- requireFalse(resolved, "pekko.persistence.cassandra.journal.support-deletes")
      _ <- requirePositiveInt(resolved, "pekko.persistence.cassandra.journal.target-partition-size")
      _ <- requireTrue(resolved, "datastax-java-driver.advanced.reconnect-on-init")
    yield CassandraPersistenceConfiguration(
      keyspace = journalKeyspace,
      contactPoints = points,
      localDatacenter = datacenter
    )

  private def validateSchema(
      session: CassandraSession,
      configuration: CassandraPersistenceConfiguration
  )(using ExecutionContext): Future[Unit] =
    for
      _ <- requireLocalDatacenter(session, configuration.localDatacenter)
      _ <- requireKeyspace(session, configuration.keyspace)
      _ <- requireTables(session, configuration.keyspace)
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

  private def one(
      session: CassandraSession,
      cql: String,
      bindValues: AnyRef*
  )(using ExecutionContext): Future[Option[com.datastax.oss.driver.api.core.cql.Row]] =
    session.selectOne(cql, bindValues*).asScala.map(optionalToOption)

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

  private def requirePositiveInt(
      config: Config,
      path: String
  ): Either[CassandraPersistenceStartupError, Unit] =
    Either.cond(
      config.hasPath(path) && config.getInt(path) > 0,
      (),
      CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(
        s"$path must be a positive integer"
      )
    )

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

  final case class InvalidCassandraPersistenceConfiguration(details: String)
      extends CassandraPersistenceStartupError:
    override def message: String = s"Invalid Cassandra persistence configuration: $details"

private final case class ValidationFailure(error: CassandraPersistenceStartupError)
    extends RuntimeException(error.message)
