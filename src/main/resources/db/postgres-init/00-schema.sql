-- Postgres mainframe-proxy schema for the mainframe-payments-demo.
--
-- The plugin's postgres-statefulset.yaml passes `-c wal_level=logical -c
-- max_replication_slots=10 -c max_wal_senders=10` to the Postgres entrypoint,
-- so Debezium's pgoutput plugin can create a logical replication slot. No
-- additional setup needed at the init-DDL layer.

-- Every table carries a `source` column ('mf' for mainframe-originated, 'gg' for
-- GridGain-originated) so the two CDC pipelines (mainframe→GG and GG→sinks)
-- can filter their own writes out of the inbound stream and avoid loops.
-- CLAUDE.md §7 — see also the gg-cache-publisher / cdc-sink filter logic.
CREATE TABLE customer (
    customer_id  BIGINT       PRIMARY KEY,
    first_name   VARCHAR(64)  NOT NULL,
    source       VARCHAR(2)   NOT NULL DEFAULT 'mf'
);

CREATE TABLE account (
    account_id   BIGINT       PRIMARY KEY,
    customer_id  BIGINT       NOT NULL REFERENCES customer(customer_id),
    balance      BIGINT       NOT NULL,  -- in cents
    source       VARCHAR(2)   NOT NULL DEFAULT 'mf'
);

CREATE TABLE product (
    product_id   BIGINT       PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    price        BIGINT       NOT NULL,  -- in cents
    source       VARCHAR(2)   NOT NULL DEFAULT 'mf'
);

CREATE TABLE transaction (
    transaction_id  BIGINT      PRIMARY KEY,
    account_id      BIGINT      NOT NULL REFERENCES account(account_id),
    product_id      BIGINT      NULL     REFERENCES product(product_id),
    amount          BIGINT      NOT NULL,  -- in cents
    type            VARCHAR(16) NOT NULL,  -- PURCHASE | PAYMENT
    occurred_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    source          VARCHAR(2)  NOT NULL DEFAULT 'mf'
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
