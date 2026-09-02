import sbt.*

object Versions {
  val scalaVersion = "3.8.4"
  val javaRelease = 21

  val pekkoVersion = "1.6.0"
  val pekkoPersistenceCassandraVersion = "1.1.0"
  val pekkoConnectorsVersion = "1.1.0"
  val tapirVersion = "1.13.19"
  val typesafeConfigVersion = "1.4.9"
  val munitVersion = "1.3.0"
  val scalaCheckVersion = "1.19.0"
  val testcontainersVersion = "2.0.5"
}

object Dependencies {
  val typesafeConfig: ModuleID =
    "com.typesafe" % "config" % Versions.typesafeConfigVersion

  private val munit: ModuleID =
    "org.scalameta" %% "munit" % Versions.munitVersion % Test

  private val munitScalaCheck: ModuleID =
    "org.scalameta" %% "munit-scalacheck" % Versions.munitVersion % Test

  private val scalaCheck: ModuleID =
    "org.scalacheck" %% "scalacheck" % Versions.scalaCheckVersion % Test

  val testDependencies: Seq[ModuleID] =
    Seq(munit, munitScalaCheck, scalaCheck)

  val pekkoRuntimeDependencies: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % Versions.pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % Versions.pekkoVersion
    )

  val pekkoRuntimeTestDependencies: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % Versions.pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % Versions.pekkoVersion % Test
    )

  val cassandraAdapterDependencies: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-cluster" % Versions.pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-tools" % Versions.pekkoVersion,
      "org.apache.pekko" %% "pekko-coordination" % Versions.pekkoVersion,
      "org.apache.pekko" %% "pekko-connectors-cassandra" % Versions.pekkoConnectorsVersion,
      "org.apache.pekko" %% "pekko-persistence-cassandra" % Versions.pekkoPersistenceCassandraVersion
    )

  val integrationTestDependencies: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % Versions.pekkoVersion % Test,
      "org.testcontainers" % "testcontainers" % Versions.testcontainersVersion % Test,
      "org.testcontainers" % "testcontainers-cassandra" % Versions.testcontainersVersion % Test
    )
}
