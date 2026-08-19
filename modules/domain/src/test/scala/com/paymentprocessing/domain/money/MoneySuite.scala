package com.paymentprocessing.domain.money

import munit.FunSuite

final class MoneySuite extends FunSuite:
  test("defines supported currencies with explicit minor-unit scale") {
    val supportedCurrencies = Currency.values.toSet

    assertEquals(supportedCurrencies, Set(Currency.PLN, Currency.EUR, Currency.USD, Currency.GBP))
    assert(Currency.values.forall(_.minorUnitScale == 2))
  }

  test("accepts positive amount with valid scale") {
    val money = Money.from(BigDecimal("10.25"), Currency.PLN)

    assertEquals(money.map(_.amount), Right(BigDecimal("10.25")))
    assertEquals(money.map(_.currency), Right(Currency.PLN))
  }

  test("rejects zero amount") {
    assertEquals(
      Money.from(BigDecimal("0.00"), Currency.EUR),
      Left(InvalidMoney.NonPositiveAmount(BigDecimal("0.00")))
    )
  }

  test("rejects negative amount") {
    assertEquals(
      Money.from(BigDecimal("-0.01"), Currency.USD),
      Left(InvalidMoney.NonPositiveAmount(BigDecimal("-0.01")))
    )
  }

  test("normalizes trailing zero precision to currency scale") {
    val money = Money.from(BigDecimal("1.000"), Currency.GBP)

    assertEquals(money.map(_.amount), Right(BigDecimal("1.00")))
    assertEquals(money.map(_.amount.bigDecimal.scale), Right(2))
  }

  test("rejects fractional precision above currency minor-unit scale") {
    assertEquals(
      Money.from(BigDecimal("1.001"), Currency.PLN),
      Left(InvalidMoney.UnsupportedScale(BigDecimal("1.001"), Currency.PLN, 2))
    )
  }

  test("keeps same amount in different currencies distinct") {
    val pln = Money.from(BigDecimal("10.00"), Currency.PLN)
    val eur = Money.from(BigDecimal("10.00"), Currency.EUR)

    assertEquals(pln.map(_.currency), Right(Currency.PLN))
    assertEquals(eur.map(_.currency), Right(Currency.EUR))
    assertNotEquals(pln, eur)
  }
