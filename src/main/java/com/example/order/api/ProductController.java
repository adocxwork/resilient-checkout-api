package com.example.order.api;

import com.example.order.repository.ProductRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository products;

    public record ProductResponse(long id, String sku, String name, BigDecimal price, String currency,
                                  int availableQuantity) {}

    @GetMapping
    public List<ProductResponse> list(@RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        return products.findAllByOrderByIdAsc(PageRequest.of(page, 25)).stream()
                .map(product -> new ProductResponse(product.getId(), product.getSku(), product.getName(),
                        product.getPrice(), "USD", product.getStockQuantity()))
                .toList();
    }
}
