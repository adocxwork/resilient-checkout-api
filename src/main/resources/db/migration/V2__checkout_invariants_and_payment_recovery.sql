-- Hibernate chose the name of the old global uniqueness constraint.
DO $$
DECLARE
    old_constraint TEXT;
BEGIN
    FOR old_constraint IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'orders'::regclass
          AND contype = 'u'
          AND conkey = ARRAY[
              (SELECT attnum FROM pg_attribute
               WHERE attrelid = 'orders'::regclass AND attname = 'idempotency_key')
          ]
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', old_constraint);
    END LOOP;
END
$$;

ALTER TABLE products
    ADD CONSTRAINT products_nonnegative_stock CHECK (stock_quantity >= 0),
    ADD CONSTRAINT products_nonnegative_price CHECK (price >= 0);

UPDATE orders SET status = 'PAYMENT_PENDING' WHERE status = 'CREATED';
UPDATE orders
SET created_at = COALESCE(created_at, updated_at, CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP AT TIME ZONE 'UTC');

ALTER TABLE orders
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD COLUMN payment_scenario VARCHAR(32),
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD CONSTRAINT orders_customer_idempotency UNIQUE (customer_id, idempotency_key),
    ADD CONSTRAINT orders_product_fk FOREIGN KEY (product_id) REFERENCES products(id),
    ADD CONSTRAINT orders_positive_quantity CHECK (quantity > 0),
    ADD CONSTRAINT orders_nonnegative_total CHECK (total_amount >= 0),
    ADD CONSTRAINT orders_valid_status CHECK (status IN ('PAYMENT_PENDING', 'PAID', 'FAILED')),
    ADD CONSTRAINT orders_valid_scenario
        CHECK (payment_scenario IN ('SUCCESS', 'DECLINE', 'TIMEOUT_AFTER_CHARGE')),
    ADD CONSTRAINT orders_payment_reference_matches_status
        CHECK (payment_scenario IS NULL OR ((payment_reference IS NOT NULL) = (status = 'PAID'))),
    ADD CONSTRAINT orders_valid_schedule
        CHECK ((next_attempt_at IS NOT NULL) = (status = 'PAYMENT_PENDING' AND payment_scenario IS NOT NULL));

CREATE INDEX orders_due_payment_idx ON orders (next_attempt_at, id)
    WHERE status = 'PAYMENT_PENDING' AND next_attempt_at IS NOT NULL;

-- This table is owned by the simulator. Its commit models an independent provider outcome.
CREATE TABLE simulated_payments (
    order_id BIGINT PRIMARY KEY,
    amount NUMERIC(38, 2) NOT NULL CHECK (amount >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'DECLINED')),
    payment_reference UUID UNIQUE,
    CHECK ((payment_reference IS NOT NULL) = (status = 'SUCCEEDED'))
);
