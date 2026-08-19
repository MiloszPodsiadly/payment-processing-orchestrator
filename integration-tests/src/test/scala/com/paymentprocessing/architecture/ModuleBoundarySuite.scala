package com.paymentprocessing.architecture

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters._

final class ModuleBoundarySuite extends FunSuite:
  private val root = Path.of("").toAbsolutePath.normalize()

  private val forbiddenImports = Map(
    "modules/domain" -> Seq(
      "org.apache.pekko",
      "sttp.tapir",
      "com.datastax",
      "com.typesafe.config",
      "org.slf4j",
      "ch.qos.logback"
    ),
    "modules/application" -> Seq(
      "sttp.tapir",
      "com.datastax",
      "org.apache.pekko.actor",
      "org.apache.pekko.persistence"
    )
  )

  test("domain and application modules do not import forbidden framework boundaries") {
    val violations =
      forbiddenImports.toSeq.flatMap { case (module, forbiddenTokens) =>
        scalaFiles(root.resolve(module)).flatMap { file =>
          val content = Files.readString(file, StandardCharsets.UTF_8)
          forbiddenTokens.collect {
            case token if content.contains(token) =>
              s"${root.relativize(file)} contains forbidden boundary token '$token'"
          }
        }
      }

    assertEquals(violations, Seq.empty)
  }

  private def scalaFiles(path: Path): Seq[Path] =
    if !Files.exists(path) then Seq.empty
    else
      val stream = Files.walk(path)
      try
        stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
          .toSeq
      finally stream.close()
