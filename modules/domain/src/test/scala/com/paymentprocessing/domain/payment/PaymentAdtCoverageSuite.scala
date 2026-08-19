package com.paymentprocessing.domain.payment

import munit.FunSuite

final class PaymentAdtCoverageSuite extends FunSuite:
  test("PaymentState transition fixture catalog covers every enum case") {
    assertEquals(
      PaymentTransitionMatrixSuite.fixtureStateLabels,
      PaymentAdtInventory.stateLabels
    )
  }

  test("PaymentCommand decision catalog covers every enum case") {
    assertEquals(
      PaymentTransitionMatrixSuite.decisionCommandLabels,
      PaymentAdtInventory.commandLabels
    )
  }

  test("PaymentEvent replay catalog covers every enum case") {
    assertEquals(
      PaymentTransitionMatrixSuite.replayEventLabels,
      PaymentAdtInventory.eventLabels
    )
  }
