-- Curated seed data for the demo's opening state: the named cast of customers,
-- their zero-balance accounts, and the curated product catalog.
--
-- NO transactions are seeded. The demo opens with $0 balances and zero
-- transactions; the mainframe panel's menu of selectable transactions is served
-- from the demo-ui resource curated-transactions.yaml (see CLAUDE.md §10), not
-- from this table. Transactions only appear once the presenter executes them.
--
-- The customer / account / product IDs here are reserved low numbers; the
-- data-generator's sequences start at 1 and will overlap, which is expected —
-- the cluster will see both seeded rows and generated rows.

-- Curated customers (a small named cast for the demo narrative).
-- All curated rows are mainframe-originated (source='mf') — the default suffices
-- but we set it explicitly for clarity.
INSERT INTO customer (customer_id, first_name, source) VALUES
    (1001, 'Raghu', 'mf'),
    (1002, 'Sonya', 'mf'),
    (1003, 'Mei',   'mf'),
    (1004, 'Diego', 'mf'),
    (1005, 'Priya', 'mf');

INSERT INTO account (account_id, customer_id, balance, source) VALUES
    (2001, 1001, 0, 'mf'),    -- Raghu:  $0.00
    (2002, 1002, 0, 'mf'),    -- Sonya:  $0.00
    (2003, 1003, 0, 'mf'),    -- Mei:    $0.00
    (2004, 1004, 0, 'mf'),    -- Diego:  $0.00
    (2005, 1005, 0, 'mf');    -- Priya:  $0.00

-- Curated product catalog seed — matches the first ~10 entries of products.yaml.
-- The full 100-row catalog is loaded by the demo setup (M4).
INSERT INTO product (product_id, name, price, source) VALUES
    (1,  'NVIDIA GeForce RTX 5080 Graphics Card',   134999, 'mf'),
    (2,  'Meta Quest 3S VR Headset',                 43550, 'mf'),
    (3,  'Apple MacBook Pro 16" M4 Max',            379900, 'mf'),
    (4,  'Sony WH-1000XM6 Wireless Headphones',      39999, 'mf'),
    (5,  'Steam Deck OLED 1TB',                      64900, 'mf'),
    (6,  'Logitech MX Master 4 Mouse',               11999, 'mf'),
    (7,  'Keychron Q1 Pro Mechanical Keyboard',      19999, 'mf'),
    (8,  'Samsung Galaxy S25 Ultra',                129999, 'mf'),
    (9,  'Google Pixel 10 Pro',                      99999, 'mf'),
    (10, 'iPhone 17 Pro Max',                       119900, 'mf');
