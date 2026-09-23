package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import com.example.order.domain.Product;
import com.example.order.repository.ProductRepository;
import java.math.BigDecimal;

@SpringBootApplication
@EnableScheduling
public class OrderProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessApplication.class, args);
    }

    @Bean
    public CommandLineRunner seedDatabase(ProductRepository productRepository) {
        return args -> {
            if (productRepository.count() == 0) {
                Product product = Product.builder()
                        .name("MacBook Pro")
                        .sku("MAC-123")
                        .price(BigDecimal.valueOf(2000.00))
                        .stockQuantity(10)
                        .build();
                productRepository.save(product);
                System.out.println("Inserted Test Product into Database!");
            }
        };
    }
}
