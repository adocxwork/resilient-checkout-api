package com.example.order.api;

import com.example.order.domain.Order;
import com.example.order.exception.OutOfStockException;
import com.example.order.exception.ProductNotFoundException;
import com.example.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    public record OrderRequest(
            @NotBlank String customerId,
            @NotNull Long productId,
            @Min(1) @NotNull Integer quantity
    ) {}

    @PostMapping
    public ResponseEntity<?> createOrder(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderRequest request
    ) {
        try {
            Order order = orderService.placeOrder(
                    idempotencyKey,
                    request.customerId(),
                    request.productId(),
                    request.quantity()
            );

            // If the order is fully paid, return 201.
            // If it failed payment, return 402 Payment Required or 201 with FAILED status based on your API design.
            // Returning 200/201 in both cases here, letting the client see the 'status' field.
            return ResponseEntity.status(HttpStatus.CREATED).body(order);

        } catch (OutOfStockException | ProductNotFoundException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An error occurred: " + e.getMessage());
        }
    }
}
