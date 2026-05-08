"""
Document processing pipeline.

Flow: S3 download → text extraction → chunking → embedding → pgvector write
      → document status update → cleanup temp file
"""

import logging
import os

from config.settings import settings
from embeddings.embedder import embed_chunks
from models.events import DocumentUploadedEvent
from processor.chunker import chunk_pages
from processor.text_extractor import extract
from storage.s3_client import download_document
from storage.vector_store import (
    document_has_chunks,
    save_chunks,
    update_document_status,
)

logger = logging.getLogger(__name__)


def process(event: DocumentUploadedEvent) -> int:
    """
    Processes a single document end-to-end.
    Returns the number of chunks created.
    Raises on unrecoverable failure (caller updates status to FAILED).
    """
    document_id = event.documentId
    tenant_id = event.tenantId

    # Idempotency: skip if already processed (at-least-once Kafka delivery)
    if document_has_chunks(document_id):
        logger.warning("Document %s already has chunks — skipping", document_id)
        return 0

    update_document_status(document_id, "PROCESSING")

    tmp_path: str | None = None
    try:
        # 1. Download from S3
        tmp_path = download_document(event.s3Key)

        # 2. Extract text (returns list of {page, text} dicts)
        pages = extract(tmp_path, event.contentType)
        if not pages:
            raise ValueError(f"No extractable text found in document {document_id}")

        # 3. Sliding-window chunk
        chunks = chunk_pages(
            pages,
            chunk_size=settings.chunk_size_chars,
            overlap=settings.chunk_overlap_chars,
        )
        logger.info("Document %s → %d chunks from %d pages", document_id, len(chunks), len(pages))

        # 4. Generate embeddings (modifies chunks in-place, adds 'embedding' key)
        embed_chunks(chunks)

        # 5. Batch-write to pgvector
        count = save_chunks(document_id, tenant_id, chunks)

        # 6. Mark COMPLETED
        update_document_status(document_id, "COMPLETED")
        return count

    except Exception as exc:
        logger.exception("Failed to process document %s: %s", document_id, exc)
        update_document_status(document_id, "FAILED", error_message=str(exc))
        raise

    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)
