-- Enable pgvector extension (needed for Phase 2 embeddings)
CREATE EXTENSION IF NOT EXISTS vector;

-- Create schemas for each service (logical isolation)
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS ingestion;

-- Grant schema permissions to the contextflow user
GRANT ALL ON SCHEMA auth TO contextflow;
GRANT ALL ON SCHEMA ingestion TO contextflow;
