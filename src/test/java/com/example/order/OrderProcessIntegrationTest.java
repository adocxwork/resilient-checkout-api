package com.example.order;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.Product;
import com.example.order.integration.PaymentClient;
import com.example.order.job.PaymentReconciliationJob;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.ProductRepository;
import com.example.order.service.OrderService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class OrderProcessIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private PaymentClient paymentClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private PaymentReconciliationJob paymentReconciliationJob;

    private Long testProductId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();

        // Create test product
        Product p = Product.builder()
                .sku("SKU-123")
                .name("Test Laptop")
                .price(BigDecimal.valueOf(1000))
                .stockQuantity(10)
                .build();
        p = productRepository.save(p);
        testProductId = p.getId();

        // Reset Circuit Breaker
        circuitBreakerRegistry.circuitBreaker("paymentService").transitionToClosedState();
    }

    @Test
    void testIdempotency_duplicateRequestsShouldReturnSameOrderAndNotDoubleCharge() throws Exception {
        // Arrange
        String idempotencyKey = UUID.randomUUID().toString();
        when(paymentClient.processPayment(eq(idempotencyKey), any()))
                .thenReturn(new PaymentClient.PaymentResult(PaymentClient.PaymentStatus.SUCCESS, "TXN-1", null));

        // Act: Fire 5 identical requests concurrently
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Order>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> 
                orderService.placeOrder(idempotencyKey, "CUST-1", testProductId, 1)
            ));
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assert
        // Only 1 order should exist in DB
        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        Order createdOrder = orders.get(0);

        // All threads should have received the SAME order (or thrown an exception safely caught by API layer)
        for (Future<Order> future : futures) {
            Order resultOrder = future.get();
            assertThat(resultOrder.getId()).isEqualTo(createdOrder.getId());
        }

        // Inventory should be decremented by 1, not 5
        Product product = productRepository.findById(testProductId).orElseThrow();
        assertThat(product.getStockQuantity()).isEqualTo(9);

        // Payment gateway should be called exactly once
        verify(paymentClient, times(1)).processPayment(eq(idempotencyKey), any());
    }

    @Test
    void testConcurrency_preventOversellingWhenStockIsLow() throws Exception {
        // Arrange: set stock to 1
        Product p = productRepository.findById(testProductId).orElseThrow();
        p.setStockQuantity(1);
        productRepository.save(p);

        when(paymentClient.processPayment(any(), any()))
                .thenReturn(new PaymentClient.PaymentResult(PaymentClient.PaymentStatus.SUCCESS, "TXN", null));

        // Act: Fire 2 concurrent requests for different users buying the same item
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Order> future1 = executor.submit(() -> orderService.placeOrder("KEY-1", "CUST-1", testProductId, 1));
        Future<Order> future2 = executor.submit(() -> orderService.placeOrder("KEY-2", "CUST-2", testProductId, 1));

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assert: One succeeds, one fails (Exception thrown internally)
        int successCount = 0;
        int failureCount = 0;
        try { future1.get(); successCount++; } catch (Exception e) { e.printStackTrace(); failureCount++; }
        try { future2.get(); successCount++; } catch (Exception e) { e.printStackTrace(); failureCount++; }

        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        // Inventory should be 0
        Product product = productRepository.findById(testProductId).orElseThrow();
        assertThat(product.getStockQuantity()).isEqualTo(0);
    }

    @Test
    void testCircuitBreaker_fallbackTriggeredWhenPaymentGatewayFails() {
        // Arrange: Make payment gateway always throw exception
        when(paymentClient.processPayment(any(), any()))
                .thenThrow(new RuntimeException("Gateway Down"));

        // Act: Trigger failures to open circuit breaker
        for (int i = 0; i < 5; i++) {
            orderService.placeOrder("KEY-CB-" + i, "CUST", testProductId, 1);
        }

        // Assert: Circuit breaker should be open
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("paymentService");
        assertThat(cb.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

        // Verify fallback was used (orders are marked as FAILED)
        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(5);
        assertThat(orders).allMatch(o -> o.getStatus() == OrderStatus.FAILED);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void testReconciliationJob_healsStuckPendingOrders() {
        // Arrange: Create a stuck order (simulating partial failure where DB didn't update to PAID)
        Order stuckOrder = Order.builder()
                .customerId("CUST-1")
                .idempotencyKey("STUCK-KEY")
                .productId(testProductId)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(1000))
                .status(OrderStatus.PAYMENT_PENDING)
                .build();
        stuckOrder = orderRepository.save(stuckOrder);

        // Hack updatedAt to be older than 1 minute using native SQL (bypassing Hibernate @UpdateTimestamp)
        jdbcTemplate.update("UPDATE orders SET updated_at = ? WHERE id = ?", 
                LocalDateTime.now().minusMinutes(2), stuckOrder.getId());

        // Upstream gateway says it actually succeeded
        when(paymentClient.getPaymentStatus("STUCK-KEY"))
                .thenReturn(new PaymentClient.PaymentResult(PaymentClient.PaymentStatus.SUCCESS, "TXN-RECOVERED", null));

        // Act
        paymentReconciliationJob.reconcilePendingPayments();

        // Assert
        Order healedOrder = orderRepository.findById(stuckOrder.getId()).orElseThrow();
        assertThat(healedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(healedOrder.getPaymentReference()).isEqualTo("TXN-RECOVERED");
    }
}
