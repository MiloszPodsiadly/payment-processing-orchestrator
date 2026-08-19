package com.paymentprocessing.domain.money

import java.math.RoundingMode

enum InvalidMoney:
  case NonPositiveAmount(amount: BigDecimal)
  case UnsupportedScale(amount: BigDecimal, currency: Currency, minorUnitScale: Int)

final class Money private (val amount: BigDecimal, val currency: Currency):
  override def equals(other: Any): Boolean =
    other match
      case that: Money => amount == that.amount && currency == that.currency
      case _ => false

  override def hashCode(): Int =
    31 * amount.hashCode + currency.hashCode

  override def toString: String =
    s"Money($amount,$currency)"

object Money:
  def from(amount: BigDecimal, currency: Currency): Either[InvalidMoney, Money] =
    if amount <= 0 then Left(InvalidMoney.NonPositiveAmount(amount))
    else
      val strippedAmount = BigDecimal(amount.bigDecimal.stripTrailingZeros)
      val normalizedScale = strippedAmount.bigDecimal.scale.max(0)

      if normalizedScale > currency.minorUnitScale then
        Left(InvalidMoney.UnsupportedScale(amount, currency, currency.minorUnitScale))
      else
        val normalizedAmount =
          BigDecimal(amount.bigDecimal.setScale(currency.minorUnitScale, RoundingMode.UNNECESSARY))

        Right(Money(normalizedAmount, currency))
