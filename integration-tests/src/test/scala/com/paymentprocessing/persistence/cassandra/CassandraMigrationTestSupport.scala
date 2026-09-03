package com.paymentprocessing.persistence.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.paymentprocessing.adapter.cassandra.CassandraJournalSchema

import scala.jdk.CollectionConverters._

object CassandraMigrationTestSupport:
  def applyMigration(session: CqlSession): Unit =
    CassandraJournalSchema.migrationCql
      .split(";")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(statement => s"$statement;")
      .foreach(session.execute)

  def tableNames(session: CqlSession, keyspace: String = CassandraJournalSchema.Keyspace): Set[String] =
    session
      .execute(
        "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?",
        keyspace
      )
      .all()
      .asScala
      .map(_.getString("table_name"))
      .toSet

  def messagesColumns(
      session: CqlSession,
      keyspace: String = CassandraJournalSchema.Keyspace
  ): Set[String] =
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
