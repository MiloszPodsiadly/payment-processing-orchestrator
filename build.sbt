import Dependencies.*
import ArchitecturePolicy.*

import java.nio.file.Files

ThisBuild / organization := "com.paymentprocessing"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := Versions.scalaVersion
ThisBuild / semanticdbEnabled := true

lazy val repositoryRoot = file(".").getAbsoluteFile
lazy val verifyArchitecture = taskKey[Unit]("Verify Phase 1 architecture boundaries.")

lazy val commonSettings = Seq(
  javacOptions ++= Seq("--release", Versions.javaRelease.toString),
  scalacOptions ++= Seq(
    "-release:" + Versions.javaRelease,
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Wvalue-discard",
    "-Wnonunit-statement"
  ),
  Test / parallelExecution := true,
  Test / fork := true,
  Test / javaOptions += s"-Dpayment.repo.root=${repositoryRoot.getAbsolutePath}",
  testFrameworks += new TestFramework("munit.Framework"),
  libraryDependencies ++= testDependencies
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
  .settings(
    name := "payment-runtime-pekko",
    libraryDependencies ++= pekkoRuntimeDependencies.map(_ % Test)
  )

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
  .settings(
    name := "payment-bootstrap",
    libraryDependencies += typesafeConfig
  )

lazy val integrationTests = project
  .in(file("integration-tests"))
  .dependsOn(bootstrap)
  .settings(commonSettings)
  .settings(
    name := "payment-integration-tests",
    libraryDependencies ++= integrationTestDependencies
  )

def projectReferenceId(reference: ProjectReference): String =
  reference match
    case ProjectRef(_, project) => project
    case LocalProject(project) => project
    case other => other.toString

def directProjectDependencies(project: ResolvedProject): Set[String] =
  project.dependencies.map(dependency => projectReferenceId(dependency.project)).toSet

def normalizedPath(value: Any): String =
  value.toString.replace('\\', '/').toLowerCase

def forbiddenDependencyViolations(
    module: String,
    classpath: Seq[Attributed[?]],
    forbiddenMarkers: Seq[String]
): Seq[String] =
  for
    dependency <- classpath
    path = normalizedPath(dependency.data)
    marker <- forbiddenMarkers
    if path.contains(marker)
  yield s"$module compile classpath contains forbidden dependency marker '$marker': $path"

ThisBuild / verifyArchitecture := Def.uncached {
  val requiredDirectories = Seq(
    "modules/domain",
    "modules/application",
    "modules/runtime-pekko",
    "modules/adapter-http-tapir",
    "modules/adapter-cassandra",
    "modules/adapter-provider",
    "modules/adapter-fraud",
    "modules/security",
    "modules/observability",
    "modules/bootstrap",
    "integration-tests"
  )

  val missingDirectories =
    requiredDirectories.filterNot(path => Files.isDirectory(repositoryRoot.toPath.resolve(path)))

  if missingDirectories.nonEmpty then
    sys.error(s"Missing required architecture directories: ${missingDirectories.mkString(", ")}")

  val approvedDomainCompileDependencies =
    infrastructureNeutralCompileDependencies

  val approvedApplicationCompileDependencies =
    infrastructureNeutralCompileDependencies +
      DependencyCoordinate(organization.value, s"payment-domain_${scalaBinaryVersion.value}")

  verifyFixtures(approvedDomainCompileDependencies)

  val approvalViolations =
    unapprovedDependencyViolations(
      "domain",
      compileDependencyCoordinates((domain / update).value),
      approvedDomainCompileDependencies
    ) ++ unapprovedDependencyViolations(
      "application",
      compileDependencyCoordinates((application / update).value),
      approvedApplicationCompileDependencies
    )

  if approvalViolations.nonEmpty then sys.error(approvalViolations.mkString(System.lineSeparator()))

  val forbiddenCompileDependencyMarkers = Map(
    "domain" -> Seq(
      "/org/apache/pekko/",
      "/sttp/tapir/",
      "/com/datastax/",
      "/com/typesafe/config/",
      "/org/slf4j/",
      "/ch/qos/logback/",
      "/io/jsonwebtoken/",
      "/com/auth0/",
      "/io/micrometer/"
    ),
    "application" -> Seq(
      "/sttp/tapir/",
      "/com/datastax/",
      "/org/apache/pekko/",
      "/io/jsonwebtoken/",
      "/com/auth0/",
      "/io/grpc/",
      "/com/squareup/okhttp/"
    )
  )

  val dependencyViolations =
    forbiddenDependencyViolations(
      "domain",
      (domain / Compile / externalDependencyClasspath).value,
      forbiddenCompileDependencyMarkers("domain")
    ) ++ forbiddenDependencyViolations(
      "application",
      (application / Compile / externalDependencyClasspath).value,
      forbiddenCompileDependencyMarkers("application")
    )

  if dependencyViolations.nonEmpty then
    sys.error(dependencyViolations.mkString(System.lineSeparator()))

  val expectedProjectGraph = Map(
    "domain" -> Set.empty[String],
    "application" -> Set("domain"),
    "runtimePekko" -> Set("application"),
    "adapterHttpTapir" -> Set("application"),
    "adapterCassandra" -> Set("application"),
    "adapterProvider" -> Set("application"),
    "adapterFraud" -> Set("application"),
    "security" -> Set("application"),
    "observability" -> Set.empty[String],
    "bootstrap" -> Set(
      "runtimePekko",
      "adapterHttpTapir",
      "adapterCassandra",
      "adapterProvider",
      "adapterFraud",
      "security",
      "observability"
    ),
    "integrationTests" -> Set("bootstrap")
  )

  val actualProjectGraph = Map(
    "domain" -> directProjectDependencies((domain / thisProject).value),
    "application" -> directProjectDependencies((application / thisProject).value),
    "runtimePekko" -> directProjectDependencies((runtimePekko / thisProject).value),
    "adapterHttpTapir" -> directProjectDependencies((adapterHttpTapir / thisProject).value),
    "adapterCassandra" -> directProjectDependencies((adapterCassandra / thisProject).value),
    "adapterProvider" -> directProjectDependencies((adapterProvider / thisProject).value),
    "adapterFraud" -> directProjectDependencies((adapterFraud / thisProject).value),
    "security" -> directProjectDependencies((security / thisProject).value),
    "observability" -> directProjectDependencies((observability / thisProject).value),
    "bootstrap" -> directProjectDependencies((bootstrap / thisProject).value),
    "integrationTests" -> directProjectDependencies((integrationTests / thisProject).value)
  )

  val graphViolations =
    expectedProjectGraph.toSeq.flatMap { case (project, expectedDependencies) =>
      val actualDependencies = actualProjectGraph(project)
      Option.when(actualDependencies != expectedDependencies)(
        s"$project depends on ${actualDependencies.toSeq.sorted.mkString(", ")}; expected ${expectedDependencies.toSeq.sorted.mkString(", ")}"
      )
    }

  if graphViolations.nonEmpty then sys.error(graphViolations.mkString(System.lineSeparator()))

  val _ =
    (integrationTests / Test / testOnly)
      .toTask(" com.paymentprocessing.architecture.ModuleBoundarySuite")
      .value
  ()
}

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
    bootstrap
  )
  .settings(
    name := "payment-processing-orchestrator",
    publish / skip := true
  )
