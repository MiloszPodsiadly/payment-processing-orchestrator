package com.paymentprocessing.domain.payment

import com.paymentprocessing.domain.identity.ProviderOperationId
import com.paymentprocessing.domain.identity.RefundId

enum PaymentError:
  case PaymentAlreadyCreated
  case PaymentNotCreated
  case InvalidStateTransition
  case OperationAlreadyInProgress(operationId: ProviderOperationId)
  case OperationMismatch(expected: ProviderOperationId, actual: ProviderOperationId)
  case ConflictingOperationOutcome(operationId: ProviderOperationId)
  case FraudRejected
  case PaymentDeclined
  case PaymentNotAuthorized
  case PaymentNotCaptured
  case PaymentAlreadyCaptured
  case PaymentAlreadyRefunded
  case RefundCurrencyMismatch
  case RefundExceedsCapturedAmount
  case DuplicateRefundConflict(refundId: RefundId)
