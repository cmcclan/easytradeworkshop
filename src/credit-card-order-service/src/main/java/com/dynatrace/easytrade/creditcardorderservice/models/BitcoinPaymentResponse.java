package com.dynatrace.easytrade.creditcardorderservice.models;

/**
 * Response returned when a bitcoin payment is accepted for asynchronous settlement.
 * The client polls the order status endpoints to observe the final outcome.
 */
public record BitcoinPaymentResponse(String creditCardOrderId, String status) {
}
