package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Request body to initiate a bitcoin payment for an existing credit card order.
 * The amount is denominated in the given fiat currency; the payment provider is
 * responsible for the fiat-to-BTC conversion at settlement time.
 */
public record BitcoinPaymentRequest(String orderId, BigDecimal amount, String currency, String walletAddress) {
    public BitcoinPaymentRequest {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(walletAddress);
    }
}
