package com.paymentprocessing.adapter.cassandra

import com.datastax.oss.driver.api.core.CqlSession

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.util.Using

object CassandraJournalSchema:
  val Keyspace: String = "pekko"
  val MigrationResource: String = "db/cassandra/migrations/V001__pekko_persistence_journal.cql"

  val RequiredTables: Set[String] =
    Set(
      "messages",
      "tag_views",
      "tag_write_progress",
      "tag_scanning",
      "metadata",
      "all_persistence_ids"
    )

  val RequiredMessagesColumns: Set[String] =
    Set(
      "persistence_id",
      "partition_nr",
      "sequence_nr",
      "timestamp",
      "timebucket",
      "writer_uuid",
      "ser_id",
      "ser_manifest",
      "event_manifest",
      "event",
      "meta_ser_id",
      "meta_ser_manifest",
      "meta",
      "tags"
    )

  def migrationCql: String =
    val stream =
      Option(Thread.currentThread().getContextClassLoader.getResourceAsStream(MigrationResource))
        .getOrElse(
          throw IllegalStateException(s"Missing Cassandra migration resource: $MigrationResource")
        )

    Using.resource(stream) { source =>
      String(source.readAllBytes(), StandardCharsets.UTF_8)
    }

  def migrationStatements: List[String] =
    migrationCql
      .split(";")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(statement => s"$statement;")
      .toList

  def applyMigration(session: CqlSession): Unit =
    migrationStatements.foreach(session.execute)

  def tableNames(session: CqlSession, keyspace: String = Keyspace): Set[String] =
    session
      .execute(
        "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?",
        keyspace
      )
      .all()
      .asScala
      .map(_.getString("table_name"))
      .toSet

  def messagesColumns(session: CqlSession, keyspace: String = Keyspace): Set[String] =
    session
      .execute(
        "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
        keyspace,
        "messages"
      )
      .all()
      .asScala
      .map(_.getString("column_name"))
      .toSet
