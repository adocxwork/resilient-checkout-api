package com.example.order.integration;

import com.example.order.domain.Order;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class PaymentIntegrationService {

    private final PaymentGateway gateway;
    private final CircuitBreaker submitBreaker = newBreaker("payment-submit");
    private final CircuitBreaker lookupBreaker = newBreaker("payment-lookup");
    private final Retry retry = Retry.of("payment-operations", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(100))
            .retryExceptions(PaymentUnavailableException.class)
            .build());

    public PaymentIntegrationService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public PaymentGateway.Result submit(Order order) {
        return invoke(submitBreaker,
                () -> gateway.submit(order.getId(), order.getTotalAmount(), order.getPaymentScenario()));
    }

    public Optional<PaymentGateway.Result> lookup(long orderId) {
        return invoke(lookupBreaker, () -> gateway.lookup(orderId));
    }

    private <T> T invoke(CircuitBreaker breaker, Supplier<T> operation) {
        // The breaker admits one logical operation and records its outcome after bounded retries.
        return breaker.executeSupplier(Retry.decorateSupplier(retry, () -> {
            try {
                return operation.get();
            } catch (TransientDataAccessException | DataAccessResourceFailureException | CannotCreateTransactionException e) {
                throw new PaymentUnavailableException(e);
            }
        }));
    }

    private static CircuitBreaker newBreaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build());
    }
}
