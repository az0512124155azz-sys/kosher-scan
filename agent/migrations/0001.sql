CREATE TABLE cases (
 id TEXT PRIMARY KEY, barcode TEXT NOT NULL, market TEXT NOT NULL,
 product_name TEXT NOT NULL DEFAULT '', brand TEXT NOT NULL DEFAULT '', image_url TEXT NOT NULL DEFAULT '',
 barcode_photo TEXT NOT NULL DEFAULT '', phase TEXT NOT NULL DEFAULT 'queued',
 ai_json TEXT NOT NULL DEFAULT '{}', ai_error TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL DEFAULT 'unknown', details TEXT NOT NULL DEFAULT '',
 evidence_url TEXT NOT NULL DEFAULT '', expires_at TEXT NOT NULL DEFAULT '',
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, reviewed_at INTEGER,
 attempts INTEGER NOT NULL DEFAULT 0, seen_count INTEGER NOT NULL DEFAULT 1,
 telegram_sent INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX cases_lookup ON cases(barcode,market,updated_at DESC);
CREATE INDEX cases_queue ON cases(phase,updated_at);
CREATE TABLE receipts (request_id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES cases(id));
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE usage (day TEXT PRIMARY KEY, ai_calls INTEGER NOT NULL DEFAULT 0, submissions INTEGER NOT NULL DEFAULT 0);
CREATE TABLE audit (id INTEGER PRIMARY KEY, case_id TEXT, action TEXT NOT NULL, created_at INTEGER NOT NULL);
