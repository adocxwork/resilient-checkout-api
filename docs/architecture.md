# Checkout architecture

The service has one Spring Boot application, one PostgreSQL database, and two kinds of tables. The checkout owns `orders` and `products`. The payment simulator owns `simulated_payments` and commits its result independently. That transaction boundary models a provider succeeding even when checkout loses its response.

## Transaction boundaries

1. **Reserve and commit.** The service takes a PostgreSQL transaction-scoped advisory lock derived from the authenticated customer and request key. It checks for a replay before taking the product row lock. In the same transaction, it deducts stock and saves a pending order. The customer-plus-key unique constraint also protects the stored invariant. A hash collision could serialize unrelated keys for the duration of this transaction.
2. **Submit payment.** Checkout calls the simulator after the reservation commits. The order ID is the simulator’s idempotency key. Its ledger permits one row per order, and every replay observes the stored outcome and reference. A confirmed decline comes back as a business result. The circuit breakers guard submission and lookup separately. Each admits one logical operation with up to three attempts for transient storage errors. A simulated lost response goes directly to recovery.
3. **Finalize and commit.** The finalization transaction locks the order before checking whether it is pending. A successful payment records the reference. A confirmed decline restores the inventory and marks the order failed in the same transaction. Other recovery attempts observe the terminal state after acquiring the lock.

Each unique key is bound to an order once the reservation commits. A validation error or an out-of-stock check can reject a request before an order and key are committed. A successful reservation retains the key for the life of its order. The response to a replay includes the current state, which can advance between requests.

## Recovery behavior

The worker selects a bounded batch ordered by next attempt time. A conditional update leases each due order before work starts. An interrupted worker becomes eligible for retry after the lease expires. Competing workers can still overlap if a lease expires during a slow operation. The simulator’s unique key and order row lock protect correctness if that happens.

| Interruption | How recovery proceeds |
| --- | --- |
| Before the reservation commits | PostgreSQL rolls back both the inventory change and the order. |
| After reservation and before submission | Lookup finds no attempt, so the worker submits under the original order ID. |
| After the simulated charge commits | Lookup returns the existing payment and its reference. |
| During the finalization transaction | A later attempt can read the committed simulator result and finalize again. |
| Gateway temporarily unavailable or breaker open | The order remains pending, with its stock held, and becomes eligible again after the lease. |

The simulator exercises the checkout state transitions without a network payment service. PostgreSQL is a shared point of failure for the checkout and simulator. The worker uses a fixed poll interval and lease, so a long outage can hold inventory until recovery. There is no automatic cancellation while the payment outcome is unknown.

## Upgrading an older local database

Flyway can adopt the original Hibernate-managed tables. Use a copy or backup before upgrading existing data. The migration adds database constraints, scopes keys to customers, and interprets legacy timestamps as UTC. Existing terminal orders are preserved. A legacy pending order has no durable provider outcome, so it stays pending and is excluded from automatic settlement. Original customer IDs are preserved. To access a legacy customer’s orders, configure an account with the original customer ID via `CHECKOUT_ALICE_USERNAME` or `CHECKOUT_BOB_USERNAME`. The authenticated username now supplies customer identity, and the request body has no `customerId` field. Existing data that violates a new check or foreign key requires repair before migration can complete.

## Deployment scope

The `demo` profile seeds a small catalog, enables deterministic failure scenarios, and provides local Basic-auth credentials. The default profile requires `CHECKOUT_DB_PASSWORD`, `CHECKOUT_ALICE_PASSWORD`, and `CHECKOUT_BOB_PASSWORD`. Database connection settings can be overridden with `CHECKOUT_DB_URL` and `CHECKOUT_DB_USER`. The application binds to loopback by default, and Compose publishes its ports on loopback.

Payment submission is synchronous in the checkout request. The service is intentionally small enough to inspect and run locally. A real deployment would need a provider contract that defines idempotency and unknown payment states, transport timeouts, production identity and TLS, and operational ownership for orders that remain pending.
