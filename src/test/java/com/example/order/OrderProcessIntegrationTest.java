package com.example.order;

import com.example.order.domain.OrderStatus;
import com.example.order.domain.PaymentScenario;
import com.example.order.integration.PaymentGateway;
import com.example.order.job.PaymentReconciliationJob;
import com.example.order.repository.OrderRepository;
import com.example.order.service.OrderService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "checkout.recovery.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderProcessIntegrationTest {

    private static final String SCHEMA = newSchema();
    private static final String DB_URL = env("CHECKOUT_TEST_DB_URL", "jdbc:postgresql://localhost:5433/orderdb");
    private static final String DB_USER = env("CHECKOUT_TEST_DB_USER", "checkout_user");
    private static final String DB_PASSWORD = env("CHECKOUT_TEST_DB_PASSWORD", "checkout_pass");
    private static final String ALICE_PASSWORD = "alice-integration-test";
    private static final String BOB_PASSWORD = "bob-integration-test";

    @DynamicPropertySource
    static void setupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> schemaUrl(SCHEMA));
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("checkout.auth.alice-password", () -> ALICE_PASSWORD);
        registry.add("checkout.auth.bob-password", () -> BOB_PASSWORD);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderRepository repository;
    @Autowired
    private OrderService orders;
    @Autowired
    private PaymentGateway gateway;
    @Autowired
    private PaymentReconciliationJob job;

    private long productId;

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("DELETE FROM simulated_payments");
        jdbc.update("DELETE FROM orders");
        jdbc.update("UPDATE products SET stock_quantity = 10 WHERE sku = 'DEMO-LAPTOP'");
        productId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM products WHERE sku = 'DEMO-LAPTOP'", Long.class));
    }

    @AfterAll
    void dropTestSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void checkoutReturnsLocationAndReplaysTheSameCustomerOrder() throws Exception {
        String body = checkoutBody(2, "SUCCESS");
        String location = mvc.perform(post("/api/orders")
                        .header("Authorization", auth("alice", ALICE_PASSWORD))
                        .header("Idempotency-Key", "order-1").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(2000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.paymentReference").isString())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andReturn().getResponse().getHeader("Location");

        long id = Long.parseLong(Objects.requireNonNull(location).substring("/api/orders/".length()));
        mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                        .header("Idempotency-Key", "order-1").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(location).header("Authorization", auth("alice", ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(location).header("Authorization", auth("bob", BOB_PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/products").header("Authorization", auth("alice", ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableQuantity").value(8));
        assertThat(stock()).isEqualTo(8);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void validatesRequestsBeforeMutatingStateAndProtectsCheckout() throws Exception {
        mvc.perform(post("/api/orders").header("Idempotency-Key", "test")
                        .contentType("application/json").content(checkoutBody(1, "SUCCESS")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                        .contentType("application/json").content(checkoutBody(1, "SUCCESS")))
                .andExpect(status().isBadRequest());

        for (String invalidBody : List.of(
                checkoutBody(0, "SUCCESS"),
                checkoutBody(101, "SUCCESS"),
                "{\"customerId\":\"bob\",\"productId\":" + productId + ",\"quantity\":1}",
                "{\"productId\":" + productId + ",\"quantity\":1.5}",
                "{\"productId\":\"" + productId + "\",\"quantity\":1}",
                "{\"productId\":" + productId + ",\"quantity\":1,\"paymentScenario\":0}")) {
            mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                            .header("Idempotency-Key", "test").contentType("application/json").content(invalidBody))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                        .header("Idempotency-Key", "not a key").contentType("application/json")
                        .content(checkoutBody(1, "SUCCESS")))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void rejectsDifferentPayloadButAllowsSameKeyForAnotherCustomer() throws Exception {
        orders.placeOrder("alice", "scoped", productId, 1, PaymentScenario.SUCCESS);

        mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                        .header("Idempotency-Key", "scoped").contentType("application/json")
                        .content(checkoutBody(2, "SUCCESS")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/api/orders").header("Authorization", auth("bob", BOB_PASSWORD))
                        .header("Idempotency-Key", "scoped").contentType("application/json")
                        .content(checkoutBody(1, "SUCCESS")))
                .andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(2);
        assertThat(paymentCount()).isEqualTo(2);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void lastUnitConcurrentRetriesAllReturnTheSameOrder() throws Exception {
        setStock(1);
        List<OrderService.CheckoutResult> results = concurrently(IntStream.range(0, 5)
                .mapToObj(index -> (Callable<OrderService.CheckoutResult>) () ->
                        orders.placeOrder("alice", "last-item", productId, 1, PaymentScenario.SUCCESS))
                .toList());

        assertThat(results.stream().filter(OrderService.CheckoutResult::created)).hasSize(1);
        assertThat(results.stream().map(result -> result.order().getId()).distinct()).hasSize(1);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(stock()).isZero();
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void differentKeysCannotOversellTheLastUnit() throws Exception {
        setStock(1);
        List<Integer> statuses = concurrently(List.of(
                () -> checkoutStatus("buyer-1"), () -> checkoutStatus("buyer-2")));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(stock()).isZero();
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void aConfirmedDeclineReleasesStockAndItsReplayDoesNotRestoreItAgain() {
        var declined = orders.placeOrder("alice", "decline", productId, 3, PaymentScenario.DECLINE);
        var replay = orders.placeOrder("alice", "decline", productId, 3, PaymentScenario.DECLINE);

        assertThat(declined.order().getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(replay.created()).isFalse();
        assertThat(replay.order().getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(stock()).isEqualTo(10);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void timeoutAfterChargeRecoversTheOriginalReferenceWithoutChargingAgain() throws Exception {
        String location = mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                        .header("Idempotency-Key", "timeout").contentType("application/json")
                        .content(checkoutBody(2, "TIMEOUT_AFTER_CHARGE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andReturn().getResponse().getHeader("Location");

        long id = Long.parseLong(Objects.requireNonNull(location).substring("/api/orders/".length()));
        String reference = gateway.lookup(id).orElseThrow().reference();
        assertThat(stock()).isEqualTo(8);
        makeDue(id);
        concurrently(List.of(runJob(), runJob()));
        job.reconcilePendingPayments();

        mvc.perform(get(location).header("Authorization", auth("alice", ALICE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentReference").value(reference));
        assertThat(stock()).isEqualTo(8);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void reconciliationCanResumeWhenTheProcessStoppedBeforeSubmittingPayment() {
        long id = createReservedOrder("crash-before-submit", PaymentScenario.SUCCESS, 1);
        assertThat(gateway.lookup(id)).isEmpty();

        job.reconcilePendingPayments();

        assertThat(orders.getOrder("alice", id).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(gateway.lookup(id)).isPresent();
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void simultaneousProviderSubmissionsRecordOnePaymentAndShareItsReference() throws Exception {
        long id = createReservedOrder("provider-idempotency", PaymentScenario.SUCCESS, 1);
        List<PaymentGateway.Result> results = concurrently(IntStream.range(0, 5)
                .mapToObj(index -> (Callable<PaymentGateway.Result>) () ->
                        gateway.submit(id, new BigDecimal("1000.00"), PaymentScenario.SUCCESS))
                .toList());

        assertThat(results.stream().distinct()).hasSize(1);
        assertThat(gateway.lookup(id)).contains(results.getFirst());
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void concurrentFinalizationRestoresADeclinedOrderExactlyOnce() throws Exception {
        long id = createReservedOrder("concurrent-finalize", PaymentScenario.DECLINE, 2);
        var result = gateway.submit(id, new BigDecimal("2000.00"), PaymentScenario.DECLINE);

        concurrently(List.of(() -> orders.finalizePayment(id, result), () -> orders.finalizePayment(id, result)));

        assertThat(orders.getOrder("alice", id).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(stock()).isEqualTo(10);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void legacyMigrationPreservesOrdersAndLeavesUnverifiablePaymentPending() {
        String legacySchema = newSchema();
        jdbc.execute("CREATE SCHEMA " + legacySchema);
        var dataSource = new DriverManagerDataSource(schemaUrl(legacySchema), DB_USER, DB_PASSWORD);
        var legacy = new JdbcTemplate(dataSource);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__checkout_schema.sql"))
                    .execute(dataSource);
            legacy.execute("ALTER TABLE orders ADD CONSTRAINT uk_original_checkout UNIQUE (idempotency_key)");
            legacy.update("INSERT INTO products (id, sku, name, price, stock_quantity) VALUES (1, 'OLD', 'Old', 20, 9)");
            legacy.update("""
                    INSERT INTO orders (customer_id, idempotency_key, product_id, quantity, total_amount, status,
                                        created_at, updated_at)
                    VALUES ('alice', 'old-key', 1, 1, 20, 'PAYMENT_PENDING', '2025-01-01 12:00:00', '2025-01-01 12:00:00')
                    """);
            Flyway.configure().dataSource(dataSource).defaultSchema(legacySchema)
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();

            assertThat(legacy.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("PAYMENT_PENDING");
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM orders WHERE next_attempt_at IS NOT NULL", Integer.class))
                    .isZero();
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM simulated_payments", Integer.class)).isZero();
            assertThat(legacy.queryForObject("SELECT stock_quantity FROM products", Integer.class)).isEqualTo(9);
            assertThat(legacy.queryForObject("""
                    SELECT created_at = TIMESTAMPTZ '2025-01-01 12:00:00+00' FROM orders
                    """, Boolean.class)).isTrue();
            assertThat(legacy.update("""
                    INSERT INTO orders (customer_id, idempotency_key, product_id, quantity, total_amount, status,
                                        created_at, updated_at)
                    VALUES ('bob', 'old-key', 1, 1, 20, 'FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA " + legacySchema + " CASCADE");
        }
    }

    private int checkoutStatus(String key) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", auth("alice", ALICE_PASSWORD))
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(checkoutBody(1, "SUCCESS")))
                .andReturn().getResponse().getStatus();
    }

    private String checkoutBody(int quantity, String scenario) {
        return """
                {"productId":%d,"quantity":%d,"paymentScenario":"%s"}
                """.formatted(productId, quantity, scenario);
    }

    private static String auth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private int stock() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId));
    }

    private void setStock(int amount) {
        jdbc.update("UPDATE products SET stock_quantity = ? WHERE id = ?", amount, productId);
    }

    private int paymentCount() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_payments", Integer.class));
    }

    private void makeDue(long id) {
        jdbc.update("UPDATE orders SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
    }

    private long createReservedOrder(String key, PaymentScenario scenario, int quantity) {
        jdbc.update("UPDATE products SET stock_quantity = stock_quantity - ? WHERE id = ?", quantity, productId);
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO orders (customer_id, idempotency_key, product_id, quantity, total_amount, status,
                                    payment_scenario, created_at, updated_at, next_attempt_at)
                VALUES ('alice', ?, ?, ?, ?, 'PAYMENT_PENDING', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP - INTERVAL '1 second')
                RETURNING id
                """, Long.class, key, productId, quantity, BigDecimal.valueOf(1000L * quantity), scenario.name()));
    }

    private Callable<Void> runJob() {
        return () -> {
            job.reconcilePendingPayments();
            return null;
        };
    }

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        var start = new CyclicBarrier(tasks.size());
        try {
            List<Callable<T>> synchronizedTasks = tasks.stream().<Callable<T>>map(task -> () -> {
                start.await(5, TimeUnit.SECONDS);
                return task.call();
            }).toList();
            var futures = executor.invokeAll(synchronizedTasks, 10, TimeUnit.SECONDS);
            List<T> results = new ArrayList<>(tasks.size());
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String newSchema() {
        return "checkout_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String schemaUrl(String schema) {
        return DB_URL + (DB_URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String env(String name, String fallback) {
        return Objects.requireNonNullElse(System.getenv(name), fallback);
    }
}
