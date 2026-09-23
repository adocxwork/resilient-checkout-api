package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Product;
import com.example.order.integration.PaymentClient;
import com.example.order.integration.PaymentIntegrationService;
import com.example.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final PaymentIntegrationService paymentIntegrationService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Entry point for order creation. Handles idempotency and orchestrates the flow.
     */
    public Order placeOrder(String idempotencyKey, String customerId, Long productId, int quantity) {
        log.info("Processing order request. Key: {}, Customer: {}, Product: {}, Qty: {}", 
                idempotencyKey, customerId, productId, quantity);

        // 1. Idempotency Check
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            log.info("Duplicate request detected for key {}. Returning existing order.", idempotencyKey);
            return existingOrder.get();
        }

        // 2. Prepare Phase: Create order in PAYMENT_PENDING state (within a transaction)
        Order pendingOrder;
        try {
            pendingOrder = transactionTemplate.execute(status -> {
                // Double check within transaction
                if (orderRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                    throw new DataIntegrityViolationException("Duplicate key");
                }

                // Deduct inventory (Uses pessimistic lock to prevent overselling)
                Product product = inventoryService.deductStock(productId, quantity);

                BigDecimal totalAmount = product.getPrice().multiply(BigDecimal.valueOf(quantity));

                // Create Order record
                Order order = Order.builder()
                        .customerId(customerId)
                        .idempotencyKey(idempotencyKey)
                        .productId(productId)
                        .quantity(quantity)
                        .totalAmount(totalAmount)
                        .status(OrderStatus.PAYMENT_PENDING)
                        .build();

                return orderRepository.saveAndFlush(order);
            });
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert caught by DB unique constraint
            log.warn("Concurrent duplicate request detected for key {} via DB constraint.", idempotencyKey);
            return orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Order should exist but not found"));
        }

        if (pendingOrder == null) {
            throw new IllegalStateException("Failed to create pending order");
        }

        // 3. Execution Phase: Process Payment (Outside the DB lock/transaction!)
        // This is crucial: we don't want to hold database locks while waiting on network calls.
        return executePaymentAndUpdateState(pendingOrder);
    }

    public Order executePaymentAndUpdateState(Order order) {
        log.info("Initiating payment for Order ID: {}, Amount: {}", order.getId(), order.getTotalAmount());
        
        PaymentClient.PaymentResult paymentResult = paymentIntegrationService.process(
                order.getIdempotencyKey(), order.getTotalAmount()
        );

        return updateOrderPaymentStatus(order.getId(), paymentResult);
    }

    @Transactional
    public Order updateOrderPaymentStatus(Long orderId, PaymentClient.PaymentResult paymentResult) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        // Ensure we don't transition backward or override a finalized state
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.FAILED) {
            log.warn("Order {} is already in final state: {}", order.getId(), order.getStatus());
            return order;
        }

        if (paymentResult.status() == PaymentClient.PaymentStatus.SUCCESS) {
            order.setStatus(OrderStatus.PAID);
            order.setPaymentReference(paymentResult.transactionId());
            log.info("Order {} payment successful. Reference: {}", order.getId(), paymentResult.transactionId());
        } else {
            order.setStatus(OrderStatus.FAILED);
            log.error("Order {} payment failed. Reason: {}", order.getId(), paymentResult.errorMessage());
        }

        return orderRepository.save(order);
    }
}
