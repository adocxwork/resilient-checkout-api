package com.example.order.service;

import com.example.order.domain.Product;
import com.example.order.exception.OutOfStockException;
import com.example.order.exception.ProductNotFoundException;
import com.example.order.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Product deductStock(Long productId, int quantity) {
        Product product = findLockedProduct(productId);
        if (product.getStockQuantity() < quantity) {
            throw new OutOfStockException("Insufficient stock for this product.");
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        return product;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreStock(Long productId, int quantity) {
        Product product = findLockedProduct(productId);
        product.setStockQuantity(Math.addExact(product.getStockQuantity(), quantity));
    }

    private Product findLockedProduct(Long productId) {
        return productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found."));
    }
}
