package com.paymentprocessing.bootstrap.config

object AppConfigEnvironmentProbe:
  def main(args: Array[String]): Unit =
    AppConfig.load() match
      case Right(config) if config.application.environment == RuntimeEnvironment.Local =>
        ()
      case Right(config) =>
        throw new IllegalStateException(
          s"Expected Local environment from process env, got ${config.application.environment}"
        )
      case Left(error) =>
        throw new IllegalStateException(error.message)
