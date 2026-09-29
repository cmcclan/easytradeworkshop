package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;

/**
 * Outbound body sent to the external bitcoin-payment-service to settle a payment.
 * Mirrors the {@link ManufactureRequest} pattern used for third-party-service.
 */
public record BitcoinSettlementRequest(String creditCardOrderId, BigDecimal amount, String currency,
        String walletAddress) {
}
