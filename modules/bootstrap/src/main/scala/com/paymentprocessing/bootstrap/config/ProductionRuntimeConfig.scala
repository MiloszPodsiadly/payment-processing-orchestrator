package com.paymentprocessing.bootstrap.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory

object ProductionRuntimeConfig:
  def load(overrides: Config = ConfigFactory.empty()): Either[ConfigError, Config] =
    try
      Right(
        overrides
          .withFallback(ConfigFactory.parseResources("application.conf"))
          .withFallback(ConfigFactory.defaultReference())
          .resolve()
      )
    catch
      case error: ConfigException =>
        Left(ConfigError(s"Invalid configuration: ${error.getMessage}"))
