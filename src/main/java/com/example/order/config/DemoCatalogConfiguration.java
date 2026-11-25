package com.example.order.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Profile("demo")
@Configuration(proxyBeanMethods = false)
class DemoCatalogConfiguration {

    @Bean
    ApplicationRunner seedDemoCatalog(JdbcTemplate jdbc) {
        return args -> jdbc.update("""
                INSERT INTO products (sku, name, price, stock_quantity) VALUES
                    ('DEMO-LAPTOP', 'Demo Laptop', 1000.00, 50),
                    ('DEMO-KEYBOARD', 'Demo Keyboard', 60.00, 50)
                ON CONFLICT (sku) DO NOTHING
                """);
    }
}
