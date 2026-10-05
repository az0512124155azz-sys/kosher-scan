CREATE TABLE observations (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES cases(id), barcode TEXT NOT NULL, market TEXT NOT NULL,
 product_name TEXT NOT NULL DEFAULT '', brand TEXT NOT NULL DEFAULT '', image_url TEXT NOT NULL DEFAULT '',
 barcode_photo TEXT NOT NULL DEFAULT '', telegram_sent INTEGER NOT NULL DEFAULT 0, delivery_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
);
CREATE INDEX observations_delivery ON observations(telegram_sent,created_at);
CREATE INDEX observations_case ON observations(case_id);
CREATE INDEX observations_lease ON observations(telegram_sent,delivery_at);
