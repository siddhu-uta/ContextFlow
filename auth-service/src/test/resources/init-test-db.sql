-- Testcontainers init script: runs before Spring Boot connects.
-- Mirrors scripts/init-db.sql but scoped to what auth-service needs.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS ingestion;
