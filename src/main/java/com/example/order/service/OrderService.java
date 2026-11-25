package com.example.order.service;

import com.example.order.config.CheckoutSettings;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.PaymentScenario;
import com.example.order.domain.Product;
import com.example.order.exception.IdempotencyConflictException;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.integration.PaymentGateway;
import com.example.order.integration.PaymentIntegrationService;
import com.example.order.integration.PaymentOutcomeUnknownException;
import com.example.order.integration.PaymentUnavailableException;
import com.example.order.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    public record CheckoutResult(Order order, boolean created) {}

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final PaymentIntegrationService payments;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CheckoutSettings settings;

    @Transactional(propagation = Propagation.NEVER)
    public CheckoutResult placeOrder(String customerId, String key, long productId, int quantity,
                                     PaymentScenario scenario) {
        CheckoutResult reservation = Objects.requireNonNull(transaction.execute(status ->
                reserve(customerId, key, productId, quantity, scenario)));
        if (reservation.created()) {
            return new CheckoutResult(attemptPayment(reservation.order(), false), true);
        }
        return reservation;
    }

    private CheckoutResult reserve(String customerId, String key, long productId, int quantity,
                                   PaymentScenario scenario) {
        // Acquire the key before inventory. A replay can then succeed even after the last unit is taken.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rows -> {
            rows.next();
            return null;
        }, customerId + ":" + key);

        Optional<Order> replay = orders.findByCustomerIdAndIdempotencyKey(customerId, key);
        if (replay.isPresent()) {
            Order existing = replay.get();
            PaymentScenario original = Objects.requireNonNullElse(existing.getPaymentScenario(), PaymentScenario.SUCCESS);
            if (existing.getProductId() != productId || existing.getQuantity() != quantity || original != scenario) {
                throw new IdempotencyConflictException();
            }
            return new CheckoutResult(existing, false);
        }

        Product product = inventory.deductStock(productId, quantity);
        Instant now = clock.instant();
        Order order = Order.builder()
                .customerId(customerId)
                .idempotencyKey(key)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(product.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .paymentScenario(scenario)
                .createdAt(now)
                .nextAttemptAt(now.plusSeconds(settings.initialDelaySeconds()))
                .build();
        return new CheckoutResult(orders.save(order), true);
    }

    public Order getOrder(String customerId, long orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId).orElseThrow(OrderNotFoundException::new);
    }

    @Transactional(propagation = Propagation.NEVER)
    public void reconcile(Order order) {
        attemptPayment(order, true);
    }

    private Order attemptPayment(Order order, boolean checkExistingPayment) {
        PaymentGateway.Result result;
        try {
            result = checkExistingPayment
                    ? payments.lookup(order.getId()).orElseGet(() -> payments.submit(order))
                    : payments.submit(order);
        } catch (PaymentUnavailableException | PaymentOutcomeUnknownException | CallNotPermittedException e) {
            log.info("Order {} remains pending after {}", order.getId(), e.getClass().getSimpleName());
            return order;
        }
        return finalizePayment(order.getId(), result);
    }

    public Order finalizePayment(long id, PaymentGateway.Result result) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Order order = orders.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
            if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
                return order;
            }

            if (result.status() == PaymentGateway.Status.SUCCEEDED) {
                order.markPaid(result.reference(), clock.instant());
            } else {
                inventory.restoreStock(order.getProductId(), order.getQuantity());
                order.markFailed(clock.instant());
            }
            return order;
        }));
    }
}
