ALTER TABLE files ADD COLUMN ai_summary        TEXT;
ALTER TABLE files ADD COLUMN ai_description   VARCHAR(500);
ALTER TABLE files ADD COLUMN ai_tags          VARCHAR(255);
ALTER TABLE files ADD COLUMN ai_processed_at  TIMESTAMP;
