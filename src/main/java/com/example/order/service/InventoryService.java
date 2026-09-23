package com.example.order.service;

import com.example.order.domain.Product;
import com.example.order.exception.OutOfStockException;
import com.example.order.exception.ProductNotFoundException;
import com.example.order.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Product deductStock(Long productId, int quantity) {
        log.info("Attempting to deduct {} from product {}", quantity, productId);
        
        // Use Pessimistic Write Lock to prevent race conditions
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product " + productId + " not found"));

        if (product.getStockQuantity() < quantity) {
            log.warn("Not enough stock for product {}. Requested: {}, Available: {}", productId, quantity, product.getStockQuantity());
            throw new OutOfStockException("Not enough stock available for product " + product.getName());
        }

        product.setStockQuantity(product.getStockQuantity() - quantity);
        return productRepository.save(product);
    }
}
