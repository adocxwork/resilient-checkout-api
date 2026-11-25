package com.example.order.repository;

import com.example.order.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    Optional<Order> findByIdAndCustomerId(Long id, String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(long id);

    @Query("""
            SELECT o FROM Order o
            WHERE o.status = com.example.order.domain.OrderStatus.PAYMENT_PENDING AND o.nextAttemptAt <= :now
            ORDER BY o.nextAttemptAt, o.id
            """)
    List<Order> findDue(Instant now, Pageable page);

    @Modifying
    @Transactional
    @Query("""
            UPDATE Order o SET o.nextAttemptAt = :leaseUntil
            WHERE o.id = :id AND o.status = com.example.order.domain.OrderStatus.PAYMENT_PENDING
                AND o.nextAttemptAt <= :now
            """)
    int claimDue(long id, Instant now, Instant leaseUntil);
}
