package com.example.order.integration;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentIntegrationService {

    private final PaymentClient paymentClient;

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    public PaymentClient.PaymentResult process(String idempotencyKey, BigDecimal amount) {
        return paymentClient.processPayment(idempotencyKey, amount);
    }
    
    @CircuitBreaker(name = "paymentService")
    @Retry(name = "paymentService")
    public PaymentClient.PaymentResult checkStatus(String idempotencyKey) {
        return paymentClient.getPaymentStatus(idempotencyKey);
    }

    public PaymentClient.PaymentResult paymentFallback(String idempotencyKey, BigDecimal amount, Throwable t) {
        log.error("Payment fallback triggered for key: {}. Reason: {}", idempotencyKey, t.getMessage());
        // Return a safe failure result rather than throwing, so the order state machine can handle it
        return new PaymentClient.PaymentResult(
                PaymentClient.PaymentStatus.FAILED, 
                null, 
                "Payment Service Unavailable: " + t.getMessage()
        );
    }
}
