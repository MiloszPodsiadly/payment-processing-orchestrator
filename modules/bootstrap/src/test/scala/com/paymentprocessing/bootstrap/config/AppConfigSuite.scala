package com.paymentprocessing.bootstrap.config

import com.typesafe.config.ConfigFactory
import munit.FunSuite

final class AppConfigSuite extends FunSuite:
  test("loads valid test configuration") {
    val config = ConfigFactory.load("application-test.conf")

    val loaded = AppConfig.load(config)

    assertEquals(loaded.map(_.application.environment), Right(RuntimeEnvironment.Test))
    assertEquals(loaded.map(_.http.interface), Right("127.0.0.1"))
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
      .withFallback(
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
      )
