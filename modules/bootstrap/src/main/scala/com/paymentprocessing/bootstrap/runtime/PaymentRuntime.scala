package com.paymentprocessing.bootstrap.runtime

import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupError
import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupValidator
import com.paymentprocessing.bootstrap.config.AppConfig
import com.paymentprocessing.bootstrap.config.ConfigError
import com.paymentprocessing.bootstrap.config.ProductionRuntimeConfig
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

object PaymentRuntime:
  final case class ReadyRuntime(system: ActorSystem[Nothing], appConfig: AppConfig)

  def start(
      overrides: Config = ConfigFactory.empty(),
      validationTimeout: FiniteDuration = 10.seconds
  ): Future[Either[CassandraPersistenceStartupError, ReadyRuntime]] =
    ProductionRuntimeConfig.load(overrides) match
      case Left(error) =>
        Future.successful(Left(toStartupError(error)))
      case Right(runtimeConfig) =>
        AppConfig.load(runtimeConfig) match
          case Left(error) =>
            Future.successful(Left(toStartupError(error)))
          case Right(appConfig) =>
            val system =
              ActorSystem[Nothing](
                Behaviors.empty[Nothing],
                appConfig.application.name,
                runtimeConfig
              )
            given scala.concurrent.ExecutionContext = system.executionContext

            CassandraPersistenceStartupValidator
              .validate(system.toClassic, validationTimeout)
              .flatMap {
                case Right(()) =>
                  Future.successful(Right(ReadyRuntime(system, appConfig)))
                case Left(error) =>
                  system.terminate()
                  system.whenTerminated.map(_ => Left(error))
              }

  private def toStartupError(error: ConfigError): CassandraPersistenceStartupError =
    CassandraPersistenceStartupError.InvalidCassandraPersistenceConfiguration(error.message)
