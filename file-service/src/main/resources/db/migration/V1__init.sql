CREATE TABLE files (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_size_bytes  BIGINT NOT NULL,
    s3_key           VARCHAR(500) NOT NULL,
    mime_type        VARCHAR(100),
    version_number   INTEGER DEFAULT 1,
    is_deleted       BOOLEAN DEFAULT FALSE,
    deleted_at       TIMESTAMP,
    is_conflict_copy BOOLEAN DEFAULT FALSE,
    original_file_id UUID,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

CREATE TABLE file_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id         UUID REFERENCES files(id),
    version_number  INTEGER NOT NULL,
    s3_key          VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_files_user_id ON files(user_id);
CREATE INDEX idx_files_deleted ON files(is_deleted, deleted_at);
CREATE INDEX idx_file_versions_file_id ON file_versions(file_id);
