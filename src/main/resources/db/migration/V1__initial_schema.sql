-- Enable pg_trgm extension for Indic trigram substring search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Document Table
CREATE TABLE document (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    language VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    tags JSONB DEFAULT '[]'::jsonb,
    year INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_document_owner ON document(owner_id);
CREATE INDEX idx_document_status ON document(status);

-- Page Table
CREATE TABLE page (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    page_no INT NOT NULL,
    image_key VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL,
    original_text TEXT,
    translated_text TEXT,
    text_hash VARCHAR(64),
    edited BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_page UNIQUE (document_id, page_no)
);

CREATE INDEX idx_page_document_id ON page(document_id);
CREATE INDEX idx_page_status ON page(status);

-- Search Indexes: Trigram on original Indic script + tsvector on English translation
CREATE INDEX idx_page_original_text_trgm ON page USING gin (original_text gin_trgm_ops);

ALTER TABLE page ADD COLUMN text_search_en tsvector 
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(translated_text, ''))) STORED;
CREATE INDEX idx_page_text_search_en ON page USING gin (text_search_en);

-- OCR Batch Table (Up to 10 pages per Sarvam Document AI job)
CREATE TABLE ocr_batch (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    page_range VARCHAR(50) NOT NULL,
    sarvam_job_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    poll_count INT NOT NULL DEFAULT 0,
    next_poll_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ocr_batch_status_poll ON ocr_batch(status, next_poll_at);

-- Stage Task Table (Idempotency and retry tracking)
CREATE TABLE stage_task (
    id UUID PRIMARY KEY,
    page_id UUID NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    stage VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_stage_task_page_stage ON stage_task(page_id, stage);

-- API Call Ledger Table (Cost and latency tracking)
CREATE TABLE api_call (
    id UUID PRIMARY KEY,
    document_id UUID REFERENCES document(id) ON DELETE SET NULL,
    endpoint VARCHAR(255) NOT NULL,
    units NUMERIC(12, 2) NOT NULL DEFAULT 0,
    unit_type VARCHAR(50) NOT NULL,
    latency_ms BIGINT NOT NULL,
    http_status INT NOT NULL,
    est_cost_inr NUMERIC(10, 4) NOT NULL DEFAULT 0.0000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_call_doc_created ON api_call(document_id, created_at);

-- Outbox Table (Transactional outbox for reliable event dispatch)
CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished ON outbox(published_at) WHERE published_at IS NULL;
