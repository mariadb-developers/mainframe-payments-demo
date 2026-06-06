-- Curated seed data for the demo's phases 1–4 (the predefined transactions
-- the presenter walks through before phase-5's generated load kicks in).
-- The customer / account / product / transaction IDs here are reserved low
-- numbers; the data-generator's sequences start at 1 and will overlap, which
-- is expected — the cluster will see both seeded rows and generated rows.

-- Curated customers (a small named cast for the demo narrative).
INSERT INTO customer (customer_id, first_name) VALUES
    (1001, 'Raghu'),
    (1002, 'Sonya'),
    (1003, 'Mei'),
    (1004, 'Diego'),
    (1005, 'Priya');

INSERT INTO account (account_id, customer_id, balance) VALUES
    (2001, 1001, 500000),    -- Raghu:  $5,000.00
    (2002, 1002, 320000),    -- Sonya:  $3,200.00
    (2003, 1003, 780000),    -- Mei:    $7,800.00
    (2004, 1004, 150000),    -- Diego:  $1,500.00
    (2005, 1005, 240000);    -- Priya:  $2,400.00

-- Curated product catalog seed — matches the first ~10 entries of products.yaml.
-- The full 100-row catalog is loaded by the demo setup (M4).
INSERT INTO product (product_id, name, price) VALUES
    (1,  'NVIDIA GeForce RTX 5080 Graphics Card',   134999),
    (2,  'Meta Quest 3S VR Headset',                 43550),
    (3,  'Apple MacBook Pro 16" M4 Max',            379900),
    (4,  'Sony WH-1000XM6 Wireless Headphones',      39999),
    (5,  'Steam Deck OLED 1TB',                      64900),
    (6,  'Logitech MX Master 4 Mouse',               11999),
    (7,  'Keychron Q1 Pro Mechanical Keyboard',      19999),
    (8,  'Samsung Galaxy S25 Ultra',                129999),
    (9,  'Google Pixel 10 Pro',                      99999),
    (10, 'iPhone 17 Pro Max',                       119900);

-- Curated transactions — the small fixed list the presenter picks from in
-- phase 1 (mainframe panel) before any GG-side activity kicks in. Spans
-- multiple customers so the phase-6 analytics queries have something
-- interesting to count.
INSERT INTO transaction (transaction_id, account_id, product_id, amount, type, occurred_at) VALUES
    (3001, 2001, 1,    134999, 'PURCHASE', NOW() - INTERVAL '7 days'),   -- Raghu buys the 5080
    (3002, 2002, 2,     43550, 'PURCHASE', NOW() - INTERVAL '6 days'),   -- Sonya buys the Quest
    (3003, 2003, 3,    379900, 'PURCHASE', NOW() - INTERVAL '5 days'),   -- Mei buys the MBP
    (3004, 2001, NULL, 100000, 'PAYMENT',  NOW() - INTERVAL '4 days'),   -- Raghu pays $1,000
    (3005, 2004, 6,     11999, 'PURCHASE', NOW() - INTERVAL '3 days'),   -- Diego buys the MX Master
    (3006, 2005, 5,     64900, 'PURCHASE', NOW() - INTERVAL '2 days'),   -- Priya buys the Steam Deck
    (3007, 2001, 7,     19999, 'PURCHASE', NOW() - INTERVAL '1 day');    -- Raghu buys a keyboard
