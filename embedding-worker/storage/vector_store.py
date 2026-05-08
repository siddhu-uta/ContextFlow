import logging
import uuid
from typing import Any

import psycopg2
from pgvector.psycopg2 import register_vector

from config.settings import settings

logger = logging.getLogger(__name__)


def _connect():
    conn = psycopg2.connect(
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_user,
        password=settings.db_password,
        options=f"-c search_path={settings.db_schema}",
    )
    register_vector(conn)
    return conn


def document_has_chunks(document_id: str) -> bool:
    """Idempotency guard — skip reprocessing if chunks already exist."""
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT EXISTS(SELECT 1 FROM document_chunks WHERE document_id = %s LIMIT 1)",
                (document_id,),
            )
            return cur.fetchone()[0]


def save_chunks(document_id: str, tenant_id: str, chunks: list[dict[str, Any]]) -> int:
    """Batch-inserts chunks with their embeddings. Returns count inserted."""
    if not chunks:
        return 0

    rows = [
        (
            str(uuid.uuid4()),
            document_id,
            tenant_id,
            c["chunk_index"],
            c["content"],
            c["embedding"],
            c.get("page_number"),
            c.get("char_start"),
            c.get("char_end"),
            c.get("token_count"),
        )
        for c in chunks
    ]

    sql = """
        INSERT INTO document_chunks
            (id, document_id, tenant_id, chunk_index, content, embedding,
             page_number, char_start, char_end, token_count)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (document_id, chunk_index) DO NOTHING
    """

    with _connect() as conn:
        with conn.cursor() as cur:
            cur.executemany(sql, rows)
        conn.commit()

    logger.info("Saved %d chunks for document %s", len(chunks), document_id)
    return len(chunks)


def update_document_status(document_id: str, status: str, error_message: str | None = None) -> None:
    sql = """
        UPDATE documents
           SET status = %s,
               error_message = %s,
               processed_at = NOW(),
               updated_at = NOW()
         WHERE id = %s
    """
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, (status, error_message, document_id))
        conn.commit()
    logger.info("Updated document %s -> %s", document_id, status)
