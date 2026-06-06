-- Postgres mainframe-proxy schema for the mainframe-payments-demo.
--
-- IMPORTANT: For Debezium logical decoding (pgoutput) to work, Postgres must
-- run with `wal_level = logical`. The default value is `replica`. This is a
-- SERVER setting and cannot be applied from initdb scripts (it requires a
-- restart). The plugin's StatefulSet manifest must pass it via container args:
--
--   args: ["-c", "wal_level=logical", "-c", "max_replication_slots=10",
--          "-c", "max_wal_senders=10"]
--
-- (Tracked for M6 — set on the Postgres image deploy.)

CREATE TABLE customer (
    customer_id  BIGINT       PRIMARY KEY,
    first_name   VARCHAR(64)  NOT NULL
);

CREATE TABLE account (
    account_id   BIGINT       PRIMARY KEY,
    customer_id  BIGINT       NOT NULL REFERENCES customer(customer_id),
    balance      BIGINT       NOT NULL  -- in cents
);

CREATE TABLE product (
    product_id   BIGINT       PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    price        BIGINT       NOT NULL  -- in cents
);

CREATE TABLE transaction (
    transaction_id  BIGINT      PRIMARY KEY,
    account_id      BIGINT      NOT NULL REFERENCES account(account_id),
    product_id      BIGINT      NULL     REFERENCES product(product_id),
    amount          BIGINT      NOT NULL,  -- in cents
    type            VARCHAR(16) NOT NULL,  -- PURCHASE | PAYMENT
    occurred_at     TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_account_customer    ON account(customer_id);
CREATE INDEX idx_transaction_account ON transaction(account_id);
CREATE INDEX idx_transaction_product ON transaction(product_id);
CREATE INDEX idx_transaction_time    ON transaction(occurred_at);

-- Debezium needs full row contents for non-key column changes to be captured.
ALTER TABLE customer    REPLICA IDENTITY FULL;
ALTER TABLE account     REPLICA IDENTITY FULL;
ALTER TABLE product     REPLICA IDENTITY FULL;
ALTER TABLE transaction REPLICA IDENTITY FULL;
