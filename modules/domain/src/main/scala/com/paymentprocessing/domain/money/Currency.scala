package com.paymentprocessing.domain.money

enum Currency(val minorUnitScale: Int):
  case PLN extends Currency(2)
  case EUR extends Currency(2)
  case USD extends Currency(2)
  case GBP extends Currency(2)
