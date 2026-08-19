package com.paymentprocessing.domain.identity

import java.util.UUID

opaque type PaymentId = UUID

object PaymentId:
  def from(value: UUID): PaymentId =
    value

  extension (id: PaymentId) def value: UUID = id

opaque type TenantId = UUID

object TenantId:
  def from(value: UUID): TenantId =
    value

  extension (id: TenantId) def value: UUID = id

opaque type CustomerId = UUID

object CustomerId:
  def from(value: UUID): CustomerId =
    value

  extension (id: CustomerId) def value: UUID = id

opaque type MerchantId = UUID

object MerchantId:
  def from(value: UUID): MerchantId =
    value

  extension (id: MerchantId) def value: UUID = id

opaque type RefundId = UUID

object RefundId:
  def from(value: UUID): RefundId =
    value

  extension (id: RefundId) def value: UUID = id

enum InvalidProviderOperationId:
  case Blank

opaque type ProviderOperationId = String

object ProviderOperationId:
  def from(value: String): Either[InvalidProviderOperationId, ProviderOperationId] =
    val normalized = value.trim
    Either.cond(normalized.nonEmpty, normalized, InvalidProviderOperationId.Blank)

  extension (id: ProviderOperationId) def value: String = id

enum InvalidPaymentMethodToken:
  case Blank

opaque type PaymentMethodToken = String

object PaymentMethodToken:
  def from(value: String): Either[InvalidPaymentMethodToken, PaymentMethodToken] =
    val normalized = value.trim
    Either.cond(normalized.nonEmpty, normalized, InvalidPaymentMethodToken.Blank)

  extension (token: PaymentMethodToken) def value: String = token
