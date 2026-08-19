ThisBuild / organization := "com.paymentprocessing"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Wvalue-discard"
  ),
  Test / parallelExecution := true
)

lazy val domain = project
  .in(file("modules/domain"))
  .settings(commonSettings)
  .settings(name := "payment-domain")

lazy val application = project
  .in(file("modules/application"))
  .dependsOn(domain)
  .settings(commonSettings)
  .settings(name := "payment-application")

lazy val runtimePekko = project
  .in(file("modules/runtime-pekko"))
  .dependsOn(application)
  .settings(commonSettings)
  .settings(name := "payment-runtime-pekko")

lazy val adapterHttpTapir = project
  .in(file("modules/adapter-http-tapir"))
  .dependsOn(application)
  .settings(commonSettings)
  .settings(name := "payment-adapter-http-tapir")

lazy val adapterCassandra = project
  .in(file("modules/adapter-cassandra"))
  .dependsOn(application)
  .settings(commonSettings)
  .settings(name := "payment-adapter-cassandra")

lazy val adapterProvider = project
  .in(file("modules/adapter-provider"))
  .dependsOn(application)
  .settings(commonSettings)
  .settings(name := "payment-adapter-provider")

lazy val adapterFraud = project
  .in(file("modules/adapter-fraud"))
  .dependsOn(application)
  .settings(commonSettings)
  .settings(name := "payment-adapter-fraud")

lazy val security = project
  .in(file("modules/security"))
  .dependsOn(application)
  .settings(commonSettings)
  .settings(name := "payment-security")

lazy val observability = project
  .in(file("modules/observability"))
  .settings(commonSettings)
  .settings(name := "payment-observability")

lazy val bootstrap = project
  .in(file("modules/bootstrap"))
  .dependsOn(
    runtimePekko,
    adapterHttpTapir,
    adapterCassandra,
    adapterProvider,
    adapterFraud,
    security,
    observability
  )
  .settings(commonSettings)
  .settings(name := "payment-bootstrap")

lazy val integrationTests = project
  .in(file("integration-tests"))
  .dependsOn(bootstrap)
  .settings(commonSettings)
  .settings(name := "payment-integration-tests")

lazy val root = project
  .in(file("."))
  .aggregate(
    domain,
    application,
    runtimePekko,
    adapterHttpTapir,
    adapterCassandra,
    adapterProvider,
    adapterFraud,
    security,
    observability,
    bootstrap,
    integrationTests
  )
  .settings(
    name := "payment-processing-orchestrator",
    publish / skip := true
  )

