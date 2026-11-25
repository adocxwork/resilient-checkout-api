package com.example.order.integration;

import com.example.order.domain.Order;
import com.example.order.domain.PaymentScenario;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIntegrationServiceTest {

    @Test
    void retriesATransientProviderFailureUpToThreeTimes() {
        AtomicInteger attempts = new AtomicInteger();
        var service = integration(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new TransientDataAccessResourceException("temporary");
            }
            return new PaymentGateway.Result(PaymentGateway.Status.SUCCEEDED, "known-reference");
        });

        assertThat(service.submit(order()).reference()).isEqualTo("known-reference");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void leavesAmbiguousPaymentForReconciliationInsteadOfImmediatelyRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        var service = integration(() -> {
            attempts.incrementAndGet();
            throw new PaymentOutcomeUnknownException();
        });

        assertThatThrownBy(() -> service.submit(order())).isInstanceOf(PaymentOutcomeUnknownException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void submissionBreakerOpensWhileStatusLookupRemainsAvailable() {
        AtomicInteger attempts = new AtomicInteger();
        var service = integration(() -> {
            attempts.incrementAndGet();
            throw new TransientDataAccessResourceException("temporary");
        });

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.submit(order())).isInstanceOf(PaymentUnavailableException.class);
        }
        assertThatThrownBy(() -> service.submit(order())).isInstanceOf(CallNotPermittedException.class);
        assertThat(attempts).hasValue(15);
        assertThat(service.lookup(1)).contains(new PaymentGateway.Result(PaymentGateway.Status.SUCCEEDED, "recorded"));
    }

    private static Order order() {
        Order order = new Order("alice", "request", 1L, 1, BigDecimal.TEN,
                PaymentScenario.SUCCESS, Instant.now(), Instant.now());
        ReflectionTestUtils.setField(order, "id", 1L);
        return order;
    }

    private static PaymentIntegrationService integration(Supplier<PaymentGateway.Result> submit) {
        return new PaymentIntegrationService(new PaymentGateway() {
            @Override
            public Result submit(long orderId, BigDecimal amount, PaymentScenario scenario) {
                return submit.get();
            }

            @Override
            public Optional<Result> lookup(long orderId) {
                return Optional.of(new Result(Status.SUCCEEDED, "recorded"));
            }
        });
    }
}
