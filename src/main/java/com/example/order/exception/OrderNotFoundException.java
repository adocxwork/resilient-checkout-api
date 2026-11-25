package com.example.order.exception;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException() {
        super("Order not found.");
    }
}
