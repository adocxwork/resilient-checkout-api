package com.example.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customerId;
    private String idempotencyKey;
    private Long productId;
    private int quantity;
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    private PaymentScenario paymentScenario;

    private String paymentReference;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant nextAttemptAt;

    @Builder
    public Order(String customerId, String idempotencyKey, Long productId, int quantity, BigDecimal totalAmount,
                 PaymentScenario paymentScenario, Instant createdAt, Instant nextAttemptAt) {
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.productId = productId;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentScenario = paymentScenario;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markPaid(String reference, Instant now) {
        status = OrderStatus.PAID;
        paymentReference = reference;
        updatedAt = now;
        nextAttemptAt = null;
    }

    public void markFailed(Instant now) {
        status = OrderStatus.FAILED;
        updatedAt = now;
        nextAttemptAt = null;
    }
}
