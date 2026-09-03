package com.paymentprocessing.adapter.cassandra

import java.nio.charset.StandardCharsets
import scala.util.Using

object CassandraJournalSchema:
  val Keyspace: String = CassandraJournalContract.CanonicalKeyspace
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

  val RequiredTablesSchema: Map[String, TableSchema] =
    Map(
      "messages" -> TableSchema(
        "messages",
        Set(
          ColumnSchema("persistence_id", "text", ColumnKind.PartitionKey, 0),
          ColumnSchema("partition_nr", "bigint", ColumnKind.PartitionKey, 1),
          ColumnSchema("sequence_nr", "bigint", ColumnKind.Clustering, 0),
          ColumnSchema("timestamp", "timeuuid", ColumnKind.Clustering, 1),
          ColumnSchema("timebucket", "text", ColumnKind.Regular, -1),
          ColumnSchema("writer_uuid", "text", ColumnKind.Regular, -1),
          ColumnSchema("ser_id", "int", ColumnKind.Regular, -1),
          ColumnSchema("ser_manifest", "text", ColumnKind.Regular, -1),
          ColumnSchema("event_manifest", "text", ColumnKind.Regular, -1),
          ColumnSchema("event", "blob", ColumnKind.Regular, -1),
          ColumnSchema("meta_ser_id", "int", ColumnKind.Regular, -1),
          ColumnSchema("meta_ser_manifest", "text", ColumnKind.Regular, -1),
          ColumnSchema("meta", "blob", ColumnKind.Regular, -1),
          ColumnSchema("tags", "set<text>", ColumnKind.Regular, -1)
        )
      ),
      "tag_views" -> TableSchema(
        "tag_views",
        Set(
          ColumnSchema("tag_name", "text", ColumnKind.PartitionKey, 0),
          ColumnSchema("timebucket", "bigint", ColumnKind.PartitionKey, 1),
          ColumnSchema("timestamp", "timeuuid", ColumnKind.Clustering, 0),
          ColumnSchema("persistence_id", "text", ColumnKind.Clustering, 1),
          ColumnSchema("tag_pid_sequence_nr", "bigint", ColumnKind.Clustering, 2),
          ColumnSchema("sequence_nr", "bigint", ColumnKind.Regular, -1),
          ColumnSchema("writer_uuid", "text", ColumnKind.Regular, -1),
          ColumnSchema("ser_id", "int", ColumnKind.Regular, -1),
          ColumnSchema("ser_manifest", "text", ColumnKind.Regular, -1),
          ColumnSchema("event_manifest", "text", ColumnKind.Regular, -1),
          ColumnSchema("event", "blob", ColumnKind.Regular, -1),
          ColumnSchema("meta_ser_id", "int", ColumnKind.Regular, -1),
          ColumnSchema("meta_ser_manifest", "text", ColumnKind.Regular, -1),
          ColumnSchema("meta", "blob", ColumnKind.Regular, -1)
        )
      ),
      "tag_write_progress" -> TableSchema(
        "tag_write_progress",
        Set(
          ColumnSchema("persistence_id", "text", ColumnKind.PartitionKey, 0),
          ColumnSchema("tag", "text", ColumnKind.Clustering, 0),
          ColumnSchema("sequence_nr", "bigint", ColumnKind.Regular, -1),
          ColumnSchema("tag_pid_sequence_nr", "bigint", ColumnKind.Regular, -1),
          ColumnSchema("offset", "timeuuid", ColumnKind.Regular, -1)
        )
      ),
      "tag_scanning" -> TableSchema(
        "tag_scanning",
        Set(
          ColumnSchema("persistence_id", "text", ColumnKind.PartitionKey, 0),
          ColumnSchema("sequence_nr", "bigint", ColumnKind.Regular, -1)
        )
      ),
      "metadata" -> TableSchema(
        "metadata",
        Set(
          ColumnSchema("persistence_id", "text", ColumnKind.PartitionKey, 0),
          ColumnSchema("deleted_to", "bigint", ColumnKind.Regular, -1),
          ColumnSchema("properties", "map<text,text>", ColumnKind.Regular, -1)
        )
      ),
      "all_persistence_ids" -> TableSchema(
        "all_persistence_ids",
        Set(ColumnSchema("persistence_id", "text", ColumnKind.PartitionKey, 0))
      )
    )

  val RequiredMessagesColumns: Set[String] =
    RequiredTablesSchema("messages").columns.map(_.name)

  def migrationCql: String =
    val stream =
      Option(Thread.currentThread().getContextClassLoader.getResourceAsStream(MigrationResource))
        .getOrElse(
          throw IllegalStateException(s"Missing Cassandra migration resource: $MigrationResource")
        )

    Using.resource(stream) { source =>
      String(source.readAllBytes(), StandardCharsets.UTF_8)
    }

final case class TableSchema(tableName: String, columns: Set[ColumnSchema])

final case class ColumnSchema(
    name: String,
    cqlType: String,
    kind: ColumnKind,
    position: Int
)

enum ColumnKind(val systemSchemaValue: String):
  case PartitionKey extends ColumnKind("partition_key")
  case Clustering extends ColumnKind("clustering")
  case Regular extends ColumnKind("regular")
