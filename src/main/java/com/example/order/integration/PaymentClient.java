package com.example.order.integration;

import com.example.order.domain.PaymentScenario;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentClient implements PaymentGateway {

    private record Entry(BigDecimal amount, Result result) {}

    private static final RowMapper<Entry> PAYMENT_ROW = (row, number) -> new Entry(
            row.getBigDecimal("amount"),
            new Result(Status.valueOf(row.getString("status")), row.getString("payment_reference"))
    );

    private final JdbcTemplate jdbc;
    private final TransactionTemplate providerTransaction;

    public PaymentClient(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        providerTransaction = new TransactionTemplate(transactionManager);
        providerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Result submit(long orderId, BigDecimal amount, PaymentScenario scenario) {
        Status status = scenario == PaymentScenario.DECLINE ? Status.DECLINED : Status.SUCCEEDED;
        UUID reference = status == Status.SUCCEEDED ? UUID.randomUUID() : null;
        Optional<Entry> inserted = Objects.requireNonNull(providerTransaction.execute(transaction -> jdbc.query("""
                INSERT INTO simulated_payments (order_id, amount, status, payment_reference)
                VALUES (?, ?, ?, CAST(? AS uuid))
                ON CONFLICT (order_id) DO NOTHING
                RETURNING amount, status, payment_reference
                """, PAYMENT_ROW, orderId, amount, status.name(), reference).stream().findFirst()));

        if (inserted.isPresent() && scenario == PaymentScenario.TIMEOUT_AFTER_CHARGE) {
            // The provider has already committed when the caller loses the response.
            throw new PaymentOutcomeUnknownException();
        }

        Entry entry = inserted.orElseGet(() -> findEntry(orderId).orElseThrow());
        if (entry.amount().compareTo(amount) != 0) {
            throw new IllegalStateException("Payment amount differs from the recorded attempt");
        }
        return entry.result();
    }

    @Override
    public Optional<Result> lookup(long orderId) {
        return findEntry(orderId).map(Entry::result);
    }

    private Optional<Entry> findEntry(long orderId) {
        return jdbc.query("""
                SELECT amount, status, payment_reference FROM simulated_payments WHERE order_id = ?
                """, PAYMENT_ROW, orderId).stream().findFirst();
    }
}
