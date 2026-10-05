ALTER TABLE cases ADD COLUMN telegram_delivery_at INTEGER NOT NULL DEFAULT 0;
UPDATE cases SET telegram_sent=1 WHERE EXISTS (SELECT 1 FROM observations WHERE case_id=cases.id AND telegram_sent=1);
UPDATE observations SET telegram_sent=0 WHERE telegram_sent=2;
CREATE INDEX IF NOT EXISTS cases_telegram_delivery ON cases(telegram_sent,telegram_delivery_at);
