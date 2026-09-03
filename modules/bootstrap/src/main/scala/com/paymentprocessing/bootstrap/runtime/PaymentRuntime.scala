package com.paymentprocessing.bootstrap.runtime

import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupError
import com.paymentprocessing.adapter.cassandra.CassandraPersistenceStartupValidator
import com.paymentprocessing.bootstrap.config.AppConfig
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
  ): Future[Either[PaymentRuntimeStartupError, ReadyRuntime]] =
    ProductionRuntimeConfig.load(overrides) match
      case Left(error) =>
        Future.successful(
          Left(PaymentRuntimeStartupError.InvalidRuntimeConfiguration(error.message))
        )
      case Right(runtimeConfig) =>
        AppConfig.load(runtimeConfig) match
          case Left(error) =>
            Future.successful(
              Left(PaymentRuntimeStartupError.InvalidRuntimeConfiguration(error.message))
            )
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
                  system.whenTerminated.map(_ =>
                    Left(PaymentRuntimeStartupError.CassandraPersistenceValidationFailed(error))
                  )
              }

sealed trait PaymentRuntimeStartupError:
  def message: String

object PaymentRuntimeStartupError:
  final case class InvalidRuntimeConfiguration(details: String) extends PaymentRuntimeStartupError:
    override def message: String = s"Invalid runtime configuration: $details"

  final case class CassandraPersistenceValidationFailed(error: CassandraPersistenceStartupError)
      extends PaymentRuntimeStartupError:
    override def message: String = error.message
