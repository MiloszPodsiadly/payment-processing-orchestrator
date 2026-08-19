package com.paymentprocessing.bootstrap.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory

final case class AppConfig(
    application: ApplicationConfig,
    http: HttpConfig,
    cassandra: CassandraConfig,
    security: SecurityConfig,
    observability: ObservabilityConfig,
    provider: ProviderConfig
)

final case class ApplicationConfig(name: String, environment: RuntimeEnvironment)

final case class HttpConfig(interface: String, port: Int)

final case class CassandraConfig(host: String, port: Int, localDatacenter: String)

final case class SecurityConfig(tokenIssuer: String)

final case class ObservabilityConfig(structuredLogging: Boolean)

final case class ProviderConfig(mode: String)

enum RuntimeEnvironment:
  case Local, Test, Production

object AppConfig:
  def load(): Either[ConfigError, AppConfig] =
    load(ConfigFactory.load())

  def load(config: Config): Either[ConfigError, AppConfig] =
    try
      val resolved = config.resolve()
      val parsed = AppConfig(
        application = ApplicationConfig(
          name = requiredNonBlank(resolved, "payment.application.name"),
          environment =
            parseEnvironment(requiredNonBlank(resolved, "payment.application.environment"))
        ),
        http = HttpConfig(
          interface = requiredNonBlank(resolved, "payment.http.interface"),
          port = requiredPort(resolved, "payment.http.port")
        ),
        cassandra = CassandraConfig(
          host = requiredNonBlank(resolved, "payment.cassandra.host"),
          port = requiredPort(resolved, "payment.cassandra.port"),
          localDatacenter = requiredNonBlank(resolved, "payment.cassandra.local-datacenter")
        ),
        security = SecurityConfig(
          tokenIssuer = requiredNonBlank(resolved, "payment.security.token-issuer")
        ),
        observability = ObservabilityConfig(
          structuredLogging = resolved.getBoolean("payment.observability.structured-logging")
        ),
        provider = ProviderConfig(
          mode = requiredNonBlank(resolved, "payment.provider.mode")
        )
      )
      validate(parsed)
      Right(parsed)
    catch
      case error: ConfigError => Left(error)
      case error: ConfigException =>
        Left(ConfigError(s"Invalid configuration: ${error.getMessage}"))

  private def validate(config: AppConfig): Unit =
    if config.application.environment == RuntimeEnvironment.Production then
      rejectProductionPlaceholder("payment.security.token-issuer", config.security.tokenIssuer)
      if config.provider.mode == "mock" then
        throw ConfigError("payment.provider.mode cannot be mock in production")

  private def requiredNonBlank(config: Config, path: String): String =
    val value = config.getString(path).trim
    if value.isEmpty then throw ConfigError(s"$path must be non-blank")
    value

  private def requiredPort(config: Config, path: String): Int =
    val value = config.getInt(path)
    if value < 1 || value > 65535 then throw ConfigError(s"$path must be between 1 and 65535")
    value

  private def parseEnvironment(value: String): RuntimeEnvironment =
    value.toLowerCase match
      case "local" => RuntimeEnvironment.Local
      case "test" => RuntimeEnvironment.Test
      case "production" => RuntimeEnvironment.Production
      case other => throw ConfigError(s"Unsupported payment.application.environment: $other")

  private def rejectProductionPlaceholder(path: String, value: String): Unit =
    val normalized = value.toLowerCase
    val unsafeValues = Set("local-dev-issuer", "change-me", "changeme", "placeholder")
    if unsafeValues.contains(normalized) then
      throw ConfigError(s"$path uses an unsafe production placeholder")

final case class ConfigError(message: String) extends RuntimeException(message)
