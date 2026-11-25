package com.example.order.api;

import com.example.order.exception.IdempotencyConflictException;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.exception.OutOfStockException;
import com.example.order.exception.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class OrderExceptionHandler {

    @ExceptionHandler({OrderNotFoundException.class, ProductNotFoundException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({IdempotencyConflictException.class, OutOfStockException.class})
    ProblemDetail conflict(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
