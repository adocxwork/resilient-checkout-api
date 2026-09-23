package com.example.order.job;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.integration.PaymentClient;
import com.example.order.integration.PaymentIntegrationService;
import com.example.order.repository.OrderRepository;
import com.example.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationJob {

    private final OrderRepository orderRepository;
    private final PaymentIntegrationService paymentIntegrationService;
    private final OrderService orderService;

    /**
     * Runs every 1 minute to find stuck PAYMENT_PENDING orders and reconcile them.
     */
    @Scheduled(fixedDelay = 60000)
    public void reconcilePendingPayments() {
        log.info("Starting Payment Reconciliation Job");

        // Find orders stuck in PAYMENT_PENDING for more than 1 minute
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(1);
        List<Order> stuckOrders = orderRepository.findByStatusAndUpdatedAtBefore(OrderStatus.PAYMENT_PENDING, cutoffTime);

        if (stuckOrders.isEmpty()) {
            log.info("No stuck orders found for reconciliation.");
            return;
        }

        log.info("Found {} stuck orders for reconciliation.", stuckOrders.size());

        for (Order order : stuckOrders) {
            try {
                reconcileOrder(order);
            } catch (Exception e) {
                log.error("Failed to reconcile order {}: {}", order.getId(), e.getMessage());
                // Continue with the next order
            }
        }
        
        log.info("Finished Payment Reconciliation Job");
    }

    private void reconcileOrder(Order order) {
        log.info("Reconciling Order {}. Checking payment status upstream...", order.getId());
        
        PaymentClient.PaymentResult status = paymentIntegrationService.checkStatus(order.getIdempotencyKey());
        
        orderService.updateOrderPaymentStatus(order.getId(), status);
        
        log.info("Order {} successfully reconciled to status {}", order.getId(), status.status());
    }
}
