package com.example.order.job;

import com.example.order.config.CheckoutSettings;
import com.example.order.domain.Order;
import com.example.order.repository.OrderRepository;
import com.example.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationJob {

    private final OrderRepository orders;
    private final OrderService service;
    private final CheckoutSettings settings;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${checkout.recovery.poll-millis}")
    public void reconcilePendingPayments() {
        for (Order order : orders.findDue(clock.instant(), PageRequest.of(0, settings.batchSize()))) {
            Instant now = clock.instant();
            try {
                if (orders.claimDue(order.getId(), now, now.plusSeconds(settings.leaseSeconds())) == 1) {
                    service.reconcile(order);
                }
            } catch (RuntimeException e) {
                log.error("Reconciliation failed for order {}", order.getId(), e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "checkout.recovery.enabled", havingValue = "true")
    static class SchedulingConfiguration {}
}
