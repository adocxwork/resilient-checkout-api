package com.example.order;

import com.example.order.config.CheckoutSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(CheckoutSettings.class)
public class OrderProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
