package com.paymentprocessing.adapter.cassandra

object CassandraJournalContract:
  val CanonicalKeyspace: String = "pekko"
  val TargetPartitionSize: Int = 500000
