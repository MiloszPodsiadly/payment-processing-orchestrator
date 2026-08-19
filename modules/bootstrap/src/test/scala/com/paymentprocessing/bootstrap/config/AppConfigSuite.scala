package com.paymentprocessing.bootstrap.config

import com.typesafe.config.ConfigFactory
import munit.FunSuite

final class AppConfigSuite extends FunSuite:
  test("loads valid test configuration") {
    val config = ConfigFactory.load("application-test.conf")

    val loaded = AppConfig.load(config)

    assertEquals(loaded.map(_.application.environment), Right(RuntimeEnvironment.Test))
    assertEquals(loaded.map(_.provider.mode), Right(ProviderMode.Mock))
    assertEquals(loaded.map(_.http.interface), Right("127.0.0.1"))
  }

  test("loads valid local configuration") {
    val loaded = AppConfig.load(validConfigWith("""payment.application.environment = "local""""))

    assertEquals(loaded.map(_.application.environment), Right(RuntimeEnvironment.Local))
  }

  test("rejects missing runtime environment") {
    val config = validConfigWithoutEnvironment()

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("payment.application.environment must be defined")
    )
  }

  test("rejects blank runtime environment") {
    val config = validConfigWith("""payment.application.environment = "   """")

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("payment.application.environment must be non-blank")
    )
  }

  test("rejects unknown runtime environment") {
    val config = validConfigWith("""payment.application.environment = "staging"""")

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("Unsupported payment.application.environment: staging")
    )
  }

  test("rejects missing mandatory configuration") {
    val config = ConfigFactory.parseString("""
      payment {
        application {
          name = "missing-http"
          environment = "test"
        }
      }
    """)

    val loaded = AppConfig.load(config)

    assert(loaded.isLeft)
    assert(loaded.left.exists(_.message.contains("payment.http")))
  }

  test("rejects invalid port values") {
    val config = validConfigWith("payment.http.port = 70000")

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("payment.http.port must be between 1 and 65535")
    )
  }

  test("canonicalizes provider mode mock regardless of case") {
    val modes = Seq("mock", "MOCK", "Mock")

    val loadedModes = modes.map { mode =>
      AppConfig
        .load(validConfigWith(s"""payment.provider.mode = "$mode""""))
        .map(_.provider.mode)
    }

    assertEquals(loadedModes, Seq.fill(modes.size)(Right(ProviderMode.Mock)))
  }

  test("rejects unknown provider mode") {
    val config = validConfigWith("""payment.provider.mode = "stripe"""")

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("Unsupported payment.provider.mode: stripe")
    )
  }

  test("rejects production runtime in the current implementation phase") {
    val modes = Seq("mock", "MOCK", "Mock")

    val loaded = modes.map { mode =>
      AppConfig
        .load(
          validConfigWith(s"""
            payment.application.environment = "production"
            payment.provider.mode = "$mode"
            payment.security.token-issuer = "production-issuer"
          """)
        )
        .left
        .map(_.message)
    }

    assertEquals(
      loaded,
      Seq.fill(modes.size)(
        Left("Production runtime is not supported in the current implementation phase")
      )
    )
  }

  test("rejects production runtime before unsupported provider wiring can be claimed") {
    val config = validConfigWith("""
      payment.application.environment = "production"
      payment.provider.mode = "stripe"
      payment.security.token-issuer = "production-issuer"
    """)

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("Unsupported payment.provider.mode: stripe")
    )
  }

  test("rejects unsafe production placeholders") {
    val config = validConfigWith("""
      payment.application.environment = "production"
      payment.security.token-issuer = "local-dev-issuer"
    """)

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("payment.security.token-issuer uses an unsafe production placeholder")
    )
  }

  private def validConfigWith(overrideConfig: String) =
    ConfigFactory
      .parseString(overrideConfig)
      .withFallback(validBaseConfig)

  private def validConfigWithoutEnvironment() =
    validBaseConfigWithoutEnvironment

  private def validBaseConfig =
    ConfigFactory.parseString("""
        payment {
          application {
            name = "payment-processing-orchestrator"
            environment = "test"
          }

          http {
            interface = "127.0.0.1"
            port = 8080
          }

          cassandra {
            host = "127.0.0.1"
            port = 9042
            local-datacenter = "datacenter1"
          }

          security {
            token-issuer = "test-issuer"
          }

          observability {
            structured-logging = false
          }

          provider {
            mode = "mock"
          }
        }
      """)

  private def validBaseConfigWithoutEnvironment =
    ConfigFactory.parseString("""
        payment {
          application {
            name = "payment-processing-orchestrator"
          }

          http {
            interface = "127.0.0.1"
            port = 8080
          }

          cassandra {
            host = "127.0.0.1"
            port = 9042
            local-datacenter = "datacenter1"
          }

          security {
            token-issuer = "test-issuer"
          }

          observability {
            structured-logging = false
          }

          provider {
            mode = "mock"
          }
        }
      """)
