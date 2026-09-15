ALTER TABLE documents ADD COLUMN retrieval_revision BIGINT NOT NULL DEFAULT 0
    CHECK (retrieval_revision >= 0);

-- Revision changes commit or roll back with the source data, including same-model re-embedding.
CREATE FUNCTION bump_document_retrieval_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        UPDATE documents SET retrieval_revision = retrieval_revision + 1 WHERE id = OLD.document_id;
    END IF;
    IF TG_OP = 'INSERT' THEN
        UPDATE documents SET retrieval_revision = retrieval_revision + 1 WHERE id = NEW.document_id;
    ELSIF TG_OP = 'UPDATE' AND NEW.document_id IS DISTINCT FROM OLD.document_id THEN
        UPDATE documents SET retrieval_revision = retrieval_revision + 1 WHERE id = NEW.document_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER parent_retrieval_revision AFTER INSERT OR UPDATE OR DELETE ON parent_chunks
    FOR EACH ROW EXECUTE FUNCTION bump_document_retrieval_revision();
CREATE TRIGGER child_retrieval_revision AFTER INSERT OR UPDATE OR DELETE ON child_chunks
    FOR EACH ROW EXECUTE FUNCTION bump_document_retrieval_revision();
CREATE TRIGGER embedding_retrieval_revision AFTER INSERT OR UPDATE OR DELETE ON child_chunk_embeddings
    FOR EACH ROW EXECUTE FUNCTION bump_document_retrieval_revision();

CREATE FUNCTION bump_document_access_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.tenant_id, NEW.owner_id, NEW.visibility, NEW.original_filename)
        IS DISTINCT FROM ROW(OLD.tenant_id, OLD.owner_id, OLD.visibility, OLD.original_filename) THEN
        NEW.retrieval_revision := OLD.retrieval_revision + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER document_access_revision
    BEFORE UPDATE OF tenant_id, owner_id, visibility, original_filename ON documents
    FOR EACH ROW EXECUTE FUNCTION bump_document_access_revision();
