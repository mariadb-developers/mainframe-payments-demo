-- MariaDB analytics-side schema for the mainframe-payments-demo. Receives
-- write-through events from the GG cluster (in parallel with the Postgres
-- mainframe-proxy — neither side feeds the other). This is the demo's
-- "modern operational target" — only the GG side writes here.
--
-- The schema mirrors the Postgres mainframe-proxy table-by-table so analytic
-- queries against MariaDB are comparable to mainframe-proxy queries.

CREATE TABLE customer (
    customer_id  BIGINT       PRIMARY KEY,
    first_name   VARCHAR(64)  NOT NULL
);

CREATE TABLE account (
    account_id   BIGINT       PRIMARY KEY,
    customer_id  BIGINT       NOT NULL,
    balance      BIGINT       NOT NULL,
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);

CREATE TABLE product (
    product_id   BIGINT       PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    price        BIGINT       NOT NULL
);

CREATE TABLE transaction (
    transaction_id  BIGINT       PRIMARY KEY,
    account_id      BIGINT       NOT NULL,
    product_id      BIGINT       NULL,
    amount          BIGINT       NOT NULL,
    type            VARCHAR(16)  NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT fk_tx_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

CREATE INDEX idx_account_customer    ON account(customer_id);
CREATE INDEX idx_transaction_account ON transaction(account_id);
CREATE INDEX idx_transaction_product ON transaction(product_id);
CREATE INDEX idx_transaction_time    ON transaction(occurred_at);
