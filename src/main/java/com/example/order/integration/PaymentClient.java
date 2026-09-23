package com.example.order.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
@Slf4j
public class PaymentClient {

    private final Random random = new Random();

    public enum PaymentStatus {
        SUCCESS,
        FAILED
    }

    public record PaymentResult(PaymentStatus status, String transactionId, String errorMessage) {}

    /**
     * Simulates a payment processing call that can fail, timeout, or succeed.
     */
    public PaymentResult processPayment(String idempotencyKey, java.math.BigDecimal amount) {
        log.info("Simulating payment process for idempotencyKey: {}, amount: {}", idempotencyKey, amount);
        
        simulateNetworkDelay();
        
        // Randomly simulate failures
        int chance = random.nextInt(100);
        
        if (chance < 10) {
            // 10% chance of hard failure (exception, representing 500 or timeout)
            log.error("Payment Gateway Timeout / 500 Error!");
            throw new RuntimeException("Payment Gateway Timeout");
        } else if (chance < 25) {
            // 15% chance of business failure (insufficient funds, etc)
            log.warn("Payment declined");
            return new PaymentResult(PaymentStatus.FAILED, null, "Insufficient funds");
        }

        // 75% chance of success
        log.info("Payment successful");
        return new PaymentResult(PaymentStatus.SUCCESS, "TXN-" + java.util.UUID.randomUUID().toString(), null);
    }

    /**
     * Simulates fetching payment status for reconciliation.
     */
    public PaymentResult getPaymentStatus(String idempotencyKey) {
        log.info("Checking payment status for idempotencyKey: {}", idempotencyKey);
        
        simulateNetworkDelay();
        
        // In a real system, the gateway knows if it succeeded or not based on the key
        // For simulation, we'll assume it succeeded 80% of the time it got stuck
        if (random.nextInt(100) < 80) {
            return new PaymentResult(PaymentStatus.SUCCESS, "TXN-" + java.util.UUID.randomUUID().toString(), null);
        } else {
            return new PaymentResult(PaymentStatus.FAILED, null, "Payment not found or failed");
        }
    }

    private void simulateNetworkDelay() {
        try {
            // Random delay between 100ms and 1000ms
            Thread.sleep(100 + random.nextInt(900));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
