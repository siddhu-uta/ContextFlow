"""
Sliding-window text chunker.

chunk_size: target characters per chunk
overlap: characters of context carried over into the next chunk (~20% of chunk_size)

Each chunk includes its source page number and character offsets so the Query
Service can reconstruct citations in Phase 3.
"""

import re
from typing import Any

PageText = dict[str, Any]
Chunk = dict[str, Any]


def chunk_pages(
    pages: list[PageText],
    chunk_size: int = 1000,
    overlap: int = 200,
) -> list[Chunk]:
    all_chunks: list[Chunk] = []
    chunk_index = 0

    for page_data in pages:
        page_chunks = _chunk_text(
            page_data["text"],
            chunk_size=chunk_size,
            overlap=overlap,
        )
        for c in page_chunks:
            c["chunk_index"] = chunk_index
            c["page_number"] = page_data["page"]
            all_chunks.append(c)
            chunk_index += 1

    return all_chunks


def _chunk_text(text: str, chunk_size: int, overlap: int) -> list[Chunk]:
    """Splits a single string into overlapping chunks, breaking on word boundaries."""
    text = _normalize_whitespace(text)
    chunks: list[Chunk] = []
    start = 0

    while start < len(text):
        end = min(start + chunk_size, len(text))

        # Snap end to a word boundary to avoid mid-word cuts
        if end < len(text):
            boundary = text.rfind(" ", start, end)
            if boundary > start:
                end = boundary

        chunk_content = text[start:end].strip()
        if chunk_content:
            chunks.append({
                "content": chunk_content,
                "char_start": start,
                "char_end": end,
                "token_count": _estimate_tokens(chunk_content),
            })

        # Slide forward, keeping `overlap` chars of context
        start = end - overlap
        if start >= end:  # safety guard against zero-length progress
            break

    return chunks


def _normalize_whitespace(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _estimate_tokens(text: str) -> int:
    """Rough token estimate: 1 token ≈ 4 characters (GPT-family heuristic)."""
    return len(text) // 4
