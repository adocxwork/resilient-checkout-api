package com.example.order.integration;

public class PaymentOutcomeUnknownException extends RuntimeException {
    public PaymentOutcomeUnknownException() {
        super("Payment response timed out");
    }
}
