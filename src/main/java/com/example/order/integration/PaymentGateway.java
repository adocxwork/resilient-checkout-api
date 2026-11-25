package com.example.order.integration;

import com.example.order.domain.PaymentScenario;

import java.math.BigDecimal;
import java.util.Optional;

public interface PaymentGateway {

    enum Status {
        SUCCEEDED,
        DECLINED
    }

    record Result(Status status, String reference) {}

    Result submit(long orderId, BigDecimal amount, PaymentScenario scenario);

    Optional<Result> lookup(long orderId);
}
