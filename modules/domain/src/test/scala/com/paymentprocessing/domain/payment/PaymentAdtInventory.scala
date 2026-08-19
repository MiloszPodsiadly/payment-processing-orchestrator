package com.paymentprocessing.domain.payment

import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.deriving.Mirror

object PaymentAdtInventory:
  inline def labelsOf[T](using mirror: Mirror.SumOf[T]): Set[String] =
    labels[mirror.MirroredElemLabels].toSet

  private inline def labels[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) => constValue[head].asInstanceOf[String] :: labels[tail]

  val stateLabels: Set[String] =
    labelsOf[PaymentState]

  val commandLabels: Set[String] =
    labelsOf[PaymentCommand]

  val eventLabels: Set[String] =
    labelsOf[PaymentEvent]
