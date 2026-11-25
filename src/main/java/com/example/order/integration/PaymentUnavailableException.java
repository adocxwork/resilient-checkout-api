package com.example.order.integration;

public class PaymentUnavailableException extends RuntimeException {
    public PaymentUnavailableException(Throwable cause) {
        super("Payment operation temporarily unavailable", cause);
    }
}
