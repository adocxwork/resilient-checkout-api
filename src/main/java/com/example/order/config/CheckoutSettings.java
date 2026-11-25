package com.example.order.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("checkout.recovery")
public record CheckoutSettings(
        @Min(1) @Max(100) int batchSize,
        @Min(1) int initialDelaySeconds,
        @Min(1) int leaseSeconds
) {}
