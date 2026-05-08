"""
Wraps sentence-transformers for batch embedding generation.

The model is loaded once at process start (expensive) and reused for all
subsequent requests. all-MiniLM-L6-v2 produces 384-dimensional vectors and
runs fast on CPU — ~1,000 sentences/sec on a modern laptop.
"""

import logging
from typing import Any

from sentence_transformers import SentenceTransformer

from config.settings import settings

logger = logging.getLogger(__name__)

_model: SentenceTransformer | None = None


def load_model() -> None:
    """Call this once during app startup to warm the model into memory."""
    global _model
    logger.info("Loading embedding model: %s", settings.embedding_model)
    _model = SentenceTransformer(settings.embedding_model)
    logger.info("Embedding model loaded.")


def embed_chunks(chunks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Adds an 'embedding' key (list[float]) to each chunk dict in-place."""
    if _model is None:
        raise RuntimeError("Model not loaded — call load_model() at startup")

    texts = [c["content"] for c in chunks]
    embeddings = _model.encode(
        texts,
        batch_size=settings.embedding_batch_size,
        show_progress_bar=False,
        convert_to_numpy=True,
    )

    for chunk, embedding in zip(chunks, embeddings):
        chunk["embedding"] = embedding.tolist()

    logger.debug("Embedded %d chunks", len(chunks))
    return chunks
