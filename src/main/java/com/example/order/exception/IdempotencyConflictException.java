package com.example.order.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("This idempotency key already belongs to a different checkout request.");
    }
}
