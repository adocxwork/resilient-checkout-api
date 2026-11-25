package com.example.order.api;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.PaymentScenario;
import com.example.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orders;

    @Value("${checkout.simulation.allow-scenarios}")
    private boolean allowScenarios;

    public record OrderRequest(
            @NotNull @Positive Long productId,
            @NotNull @Positive @Max(100) Integer quantity,
            PaymentScenario paymentScenario
    ) {}

    public record OrderResponse(long id, long productId, int quantity, BigDecimal totalAmount, String currency,
                                OrderStatus status, String paymentReference, Instant createdAt, Instant updatedAt) {
        static OrderResponse from(Order order) {
            return new OrderResponse(order.getId(), order.getProductId(), order.getQuantity(), order.getTotalAmount(),
                    "USD", order.getStatus(), order.getPaymentReference(), order.getCreatedAt(), order.getUpdatedAt());
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String key,
            @Valid @RequestBody OrderRequest request,
            Principal principal) {
        PaymentScenario scenario = Objects.requireNonNullElse(request.paymentScenario(), PaymentScenario.SUCCESS);
        if (!allowScenarios && scenario != PaymentScenario.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment scenarios require the demo profile.");
        }
        var result = orders.placeOrder(principal.getName(), key, request.productId(), request.quantity(), scenario);
        OrderResponse response = OrderResponse.from(result.order());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/orders/" + response.id())).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable @Positive long id, Principal principal) {
        return OrderResponse.from(orders.getOrder(principal.getName(), id));
    }
}
