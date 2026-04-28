CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE file_embeddings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id     UUID REFERENCES files(id),
    user_id     UUID NOT NULL,
    embedding   vector(1536),
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_file_embeddings_user
    ON file_embeddings(user_id);

CREATE INDEX idx_file_embeddings_vector
    ON file_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
