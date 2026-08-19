package com.paymentprocessing.architecture

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.jdk.CollectionConverters._

final class ModuleBoundarySuite extends FunSuite:
  private val root = ModuleBoundaryRules.repositoryRoot

  test("domain and application modules do not import forbidden framework boundaries") {
    val violations = ModuleBoundaryRules.checkSourceBoundaries(root)

    assertEquals(violations, Seq.empty)
  }

  test("source boundary check fails when an expected module directory is missing") {
    val fixtureRoot = Files.createTempDirectory("payment-architecture-missing-module")
    try
      val rules = Seq(
        SourceBoundaryRule(
          moduleName = "domain",
          relativePath = Path.of("modules/domain"),
          forbiddenTokens = Seq(ForbiddenSourceToken("Pekko", "org.apache.pekko"))
        )
      )

      val violations = ModuleBoundaryRules.checkSourceBoundaries(fixtureRoot, rules)

      assertEquals(
        violations,
        Seq("Missing required module directory for domain: modules/domain")
      )
    finally deleteRecursively(fixtureRoot)
  }

  test("source boundary check detects a forbidden import in a controlled fixture") {
    val fixtureRoot = Files.createTempDirectory("payment-architecture-forbidden-import")
    try
      val sourceDirectory = fixtureRoot.resolve("modules/domain/src/main/scala/example")
      Files.createDirectories(sourceDirectory)
      Files.writeString(
        sourceDirectory.resolve("ForbiddenImport.scala"),
        "package example\n\nimport org.apache.pekko.actor.typed.ActorRef\n",
        StandardCharsets.UTF_8
      )

      val rules = Seq(
        SourceBoundaryRule(
          moduleName = "domain",
          relativePath = Path.of("modules/domain"),
          forbiddenTokens = Seq(ForbiddenSourceToken("Pekko", "org.apache.pekko"))
        )
      )

      val violations = ModuleBoundaryRules.checkSourceBoundaries(fixtureRoot, rules)

      assertEquals(
        violations,
        Seq(
          "modules/domain/src/main/scala/example/ForbiddenImport.scala contains forbidden Pekko boundary token 'org.apache.pekko'"
        )
      )
    finally deleteRecursively(fixtureRoot)
  }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try
        stream
          .iterator()
          .asScala
          .toSeq
          .sortWith((left, right) => Comparator.reverseOrder[Path]().compare(left, right) < 0)
          .foreach(Files.deleteIfExists)
      finally stream.close()

private final case class ForbiddenSourceToken(label: String, token: String)

private final case class SourceBoundaryRule(
    moduleName: String,
    relativePath: Path,
    forbiddenTokens: Seq[ForbiddenSourceToken]
)

private object ModuleBoundaryRules:
  val defaultSourceRules: Seq[SourceBoundaryRule] =
    Seq(
      SourceBoundaryRule(
        moduleName = "domain",
        relativePath = Path.of("modules/domain"),
        forbiddenTokens = Seq(
          ForbiddenSourceToken("Pekko", "org.apache.pekko"),
          ForbiddenSourceToken("Tapir", "sttp.tapir"),
          ForbiddenSourceToken("Cassandra", "com.datastax"),
          ForbiddenSourceToken("Typesafe Config", "com.typesafe.config"),
          ForbiddenSourceToken("SLF4J", "org.slf4j"),
          ForbiddenSourceToken("Logback", "ch.qos.logback")
        )
      ),
      SourceBoundaryRule(
        moduleName = "application",
        relativePath = Path.of("modules/application"),
        forbiddenTokens = Seq(
          ForbiddenSourceToken("Tapir", "sttp.tapir"),
          ForbiddenSourceToken("Cassandra", "com.datastax"),
          ForbiddenSourceToken("Pekko Actor Runtime", "org.apache.pekko.actor"),
          ForbiddenSourceToken("Pekko Persistence", "org.apache.pekko.persistence")
        )
      )
    )

  def repositoryRoot: Path =
    sys.props
      .get("payment.repo.root")
      .map(Path.of(_).toAbsolutePath.normalize())
      .getOrElse(
        throw new IllegalStateException(
          "payment.repo.root system property must be provided by sbt"
        )
      )

  def checkSourceBoundaries(
      root: Path,
      rules: Seq[SourceBoundaryRule] = defaultSourceRules
  ): Seq[String] =
    rules.flatMap { rule =>
      val modulePath = root.resolve(rule.relativePath).normalize()
      if !Files.isDirectory(modulePath) then
        Seq(
          s"Missing required module directory for ${rule.moduleName}: ${renderPath(rule.relativePath)}"
        )
      else
        scalaFiles(modulePath).flatMap { file =>
          val content = Files.readString(file, StandardCharsets.UTF_8)
          rule.forbiddenTokens.collect {
            case forbidden if content.contains(forbidden.token) =>
              s"${renderPath(root.relativize(file))} contains forbidden ${forbidden.label} boundary token '${forbidden.token}'"
          }
        }
    }

  private def renderPath(path: Path): String =
    path.toString.replace('\\', '/')

  private def scalaFiles(path: Path): Seq[Path] =
    val stream = Files.walk(path)
    try
      stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .toSeq
    finally stream.close()
