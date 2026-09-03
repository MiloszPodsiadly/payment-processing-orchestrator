package com.paymentprocessing.bootstrap.config

import com.paymentprocessing.adapter.cassandra.CassandraJournalContract
import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupValidator
import com.paymentprocessing.bootstrap.runtime.PaymentRuntime
import com.paymentprocessing.bootstrap.runtime.PaymentRuntimeStartupError
import com.typesafe.config.ConfigFactory
import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

final class AppConfigSuite extends FunSuite:
  test("loads valid test configuration") {
    val config = ConfigFactory.load("application-test.conf")

    val loaded = AppConfig.load(config)

    assertEquals(loaded.map(_.application.environment), Right(RuntimeEnvironment.Test))
    assertEquals(loaded.map(_.provider.mode), Right(ProviderMode.Mock))
    assertEquals(loaded.map(_.http.interface), Right("127.0.0.1"))
    assertEquals(loaded.map(_.cassandra.localDatacenter), Right("datacenter1"))
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

  test("rejects invalid Cassandra port values") {
    val config = validConfigWith("payment.cassandra.port = 70000")

    val loaded = AppConfig.load(config)

    assertEquals(
      loaded.left.map(_.message),
      Left("payment.cassandra.port must be between 1 and 65535")
    )
  }

  test("rejects blank Cassandra host and local datacenter") {
    val invalidValues = Seq(
      "payment.cassandra.host" -> "payment.cassandra.host must be non-blank",
      "payment.cassandra.local-datacenter" -> "payment.cassandra.local-datacenter must be non-blank"
    )

    val loaded = invalidValues.map { case (path, _) =>
      AppConfig.load(validConfigWith(s"""$path = "   """")).left.map(_.message)
    }

    assertEquals(loaded, invalidValues.map { case (_, error) => Left(error) })
  }

  test("resolves Cassandra journal contact point from typed payment config") {
    val resolved =
      ProductionRuntimeConfig
        .load(
          ConfigFactory.parseString("""
            payment.application.environment = "test"
            payment.cassandra.host = "10.20.30.40"
            payment.cassandra.port = 9142
            payment.cassandra.local-datacenter = "phase4dc"
          """)
        )
        .toOption
        .get

    assertEquals(
      resolved.getString("pekko.persistence.journal.plugin"),
      "pekko.persistence.cassandra.journal"
    )
    assertEquals(
      resolved.getString("pekko.persistence.cassandra.journal.keyspace"),
      CassandraJournalContract.CanonicalKeyspace
    )
    assertEquals(
      resolved.getStringList("datastax-java-driver.basic.contact-points").get(0),
      "10.20.30.40:9142"
    )
    assertEquals(
      resolved.getString("datastax-java-driver.basic.load-balancing-policy.local-datacenter"),
      "phase4dc"
    )
  }

  test("production runtime config rejects unresolved substitutions as typed configuration errors") {
    val loaded =
      ProductionRuntimeConfig.load(
        ConfigFactory.parseString("""payment.cassandra.host = ${MISSING_PAYMENT_CASSANDRA_HOST}""")
      )

    assert(loaded.left.exists(_.message.startsWith("Invalid configuration:")))
  }

  test("production runtime config owns journal policy as one source of truth") {
    val resolved =
      ProductionRuntimeConfig
        .load(ConfigFactory.parseString("""payment.application.environment = "test""""))
        .toOption
        .get

    assertEquals(
      resolved.getString("pekko.persistence.cassandra.journal.keyspace"),
      CassandraJournalContract.CanonicalKeyspace
    )
    assertEquals(
      resolved.getInt("pekko.persistence.cassandra.journal.target-partition-size"),
      CassandraJournalContract.TargetPartitionSize
    )
    assertEquals(
      CassandraPersistenceStartupValidator.validateConfiguration(resolved).map(_.keyspace),
      Right(CassandraJournalContract.CanonicalKeyspace)
    )
  }

  test("Cassandra persistence config validation rejects non-canonical journal contract changes") {
    val wrongKeyspace =
      ConfigFactory
        .parseString("""
          payment.application.environment = "test"
          pekko.persistence.cassandra.journal.keyspace = "payment_journal"
        """)
        .withFallback(resolvedValidRuntimeConfig)
        .resolve()
    val wrongPartitionSize =
      ConfigFactory
        .parseString("""
          payment.application.environment = "test"
          pekko.persistence.cassandra.journal.target-partition-size = 1
        """)
        .withFallback(resolvedValidRuntimeConfig)
        .resolve()

    assertEquals(
      CassandraPersistenceStartupValidator.validateConfiguration(wrongKeyspace).left.map(_.message),
      Left(
        "Invalid Cassandra persistence configuration: pekko.persistence.cassandra.journal.keyspace must be 'pekko', got 'payment_journal'"
      )
    )
    assertEquals(
      CassandraPersistenceStartupValidator
        .validateConfiguration(wrongPartitionSize)
        .left
        .map(_.message),
      Left(
        "Invalid Cassandra persistence configuration: pekko.persistence.cassandra.journal.target-partition-size must be 500000"
      )
    )
  }

  test(
    "Cassandra persistence config validation converts wrong typed config values to typed errors"
  ) {
    val wrongTypedDeletes =
      ConfigFactory
        .parseString("""
          payment.application.environment = "test"
          pekko.persistence.cassandra.journal.support-deletes = 42
        """)
        .withFallback(resolvedValidRuntimeConfig)
        .resolve()

    val loaded = CassandraPersistenceStartupValidator.validateConfiguration(wrongTypedDeletes)

    assert(loaded.left.exists { case error =>
      error.message.startsWith("Invalid Cassandra persistence configuration:")
    })
  }

  test("PaymentRuntime.start reports non-Cassandra runtime config as bootstrap runtime error") {
    val started =
      Await.result(
        PaymentRuntime.start(validConfigWith("payment.http.port = 70000"), 1.second),
        3.seconds
      )

    assertEquals(
      started.left.map(_.message),
      Left("Invalid runtime configuration: payment.http.port must be between 1 and 65535")
    )
    assert(started.left.exists {
      case _: PaymentRuntimeStartupError.InvalidRuntimeConfiguration => true
      case _: PaymentRuntimeStartupError.CassandraPersistenceValidationFailed => false
    })
  }

  test("Cassandra persistence config validation rejects unsafe journal defaults") {
    val unsafeAutocreate =
      ConfigFactory
        .parseString("pekko.persistence.cassandra.journal.keyspace-autocreate = true")
        .withFallback(resolvedValidRuntimeConfig)
        .resolve()
    val unsafeTables =
      ConfigFactory
        .parseString("pekko.persistence.cassandra.journal.tables-autocreate = true")
        .withFallback(resolvedValidRuntimeConfig)
        .resolve()
    val unsafeDeletes =
      ConfigFactory
        .parseString("pekko.persistence.cassandra.journal.support-deletes = true")
        .withFallback(resolvedValidRuntimeConfig)
        .resolve()

    assertEquals(
      CassandraPersistenceStartupValidator
        .validateConfiguration(unsafeAutocreate)
        .left
        .map(
          _.message
        ),
      Left(
        "Invalid Cassandra persistence configuration: pekko.persistence.cassandra.journal.keyspace-autocreate must be false"
      )
    )
    assertEquals(
      CassandraPersistenceStartupValidator.validateConfiguration(unsafeTables).left.map(_.message),
      Left(
        "Invalid Cassandra persistence configuration: pekko.persistence.cassandra.journal.tables-autocreate must be false"
      )
    )
    assertEquals(
      CassandraPersistenceStartupValidator.validateConfiguration(unsafeDeletes).left.map(_.message),
      Left(
        "Invalid Cassandra persistence configuration: pekko.persistence.cassandra.journal.support-deletes must be false"
      )
    )
  }

  test("Cassandra persistence config validation rejects missing required driver mapping") {
    val missingContactPoint =
      ConfigFactory
        .parseString("""
          pekko.persistence.journal.plugin = "pekko.persistence.cassandra.journal"
          pekko.persistence.cassandra.journal {
            keyspace = "pekko"
            keyspace-autocreate = false
            tables-autocreate = false
            support-deletes = false
            target-partition-size = 500000
          }
          datastax-java-driver.advanced.reconnect-on-init = true
          datastax-java-driver.basic.load-balancing-policy.local-datacenter = "datacenter1"
        """)
        .resolve()

    assertEquals(
      CassandraPersistenceStartupValidator
        .validateConfiguration(missingContactPoint)
        .left
        .map(
          _.message
        ),
      Left(
        "Invalid Cassandra persistence configuration: datastax-java-driver.basic.contact-points must be defined"
      )
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

  private def resolvedValidRuntimeConfig =
    ConfigFactory
      .parseString("""payment.application.environment = "test"""")
      .withFallback(ConfigFactory.load())
      .resolve()

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
