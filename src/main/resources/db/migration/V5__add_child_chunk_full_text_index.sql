CREATE INDEX idx_child_chunks_text_fts
    ON child_chunks
    USING GIN (to_tsvector('english', text));
