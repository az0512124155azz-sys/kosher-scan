ALTER TABLE cases ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS cases_research_retry ON cases(phase,retry_after,created_at);
