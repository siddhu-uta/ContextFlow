"""
Unit tests for the sliding-window text chunker.

All tests run in-process with no external dependencies.
"""

import pytest
from processor.chunker import chunk_pages, _chunk_text, _normalize_whitespace, _estimate_tokens


# ── _normalize_whitespace ─────────────────────────────────────────────────────

def test_normalize_collapses_multiple_spaces():
    assert _normalize_whitespace("hello   world") == "hello world"


def test_normalize_trims_leading_trailing():
    assert _normalize_whitespace("  hello  ") == "hello"


def test_normalize_collapses_newlines_and_tabs():
    assert _normalize_whitespace("line1\n\nline2\ttab") == "line1 line2 tab"


# ── _estimate_tokens ──────────────────────────────────────────────────────────

def test_estimate_tokens_is_quarter_char_length():
    assert _estimate_tokens("abcd") == 1        # 4 chars → 1 token
    assert _estimate_tokens("a" * 100) == 25    # 100 chars → 25 tokens


def test_estimate_tokens_empty_string():
    assert _estimate_tokens("") == 0


# ── _chunk_text ───────────────────────────────────────────────────────────────

def test_chunk_text_short_text_produces_one_chunk():
    text = "Short text that fits in one chunk."
    chunks = _chunk_text(text, chunk_size=500, overlap=50)
    assert len(chunks) == 1
    assert chunks[0]["content"] == text


def test_chunk_text_long_text_splits_into_multiple():
    # 300-char word-boundary-safe text → 3 chunks at size 100, overlap 20
    text = " ".join(["word"] * 80)  # ~400 chars
    chunks = _chunk_text(text, chunk_size=100, overlap=20)
    assert len(chunks) > 1


def test_chunk_text_no_mid_word_cuts():
    text = "alpha beta gamma delta epsilon zeta eta theta iota kappa"
    chunks = _chunk_text(text, chunk_size=20, overlap=5)
    for chunk in chunks:
        content = chunk["content"]
        # A chunk must not start or end in the middle of a word
        # (unless it is the first/last character of the full text)
        assert not content.startswith(" ")
        assert not content.endswith(" ")


def test_chunk_text_overlap_carries_context():
    # With overlap, consecutive chunks should share content
    text = " ".join([f"word{i}" for i in range(50)])
    chunks = _chunk_text(text, chunk_size=60, overlap=20)

    if len(chunks) >= 2:
        # The end of chunk[0] overlaps with the start of chunk[1]
        end_of_first = chunks[0]["content"][-10:]
        start_of_second = chunks[1]["content"][:30]
        # Some words from the end of chunk 0 must appear at the start of chunk 1
        words_end = set(end_of_first.split())
        words_start = set(start_of_second.split())
        assert words_end & words_start, "Expected overlap content between consecutive chunks"


def test_chunk_text_char_offsets_present():
    text = "The quick brown fox jumps over the lazy dog."
    chunks = _chunk_text(text, chunk_size=500, overlap=0)
    assert len(chunks) == 1
    assert "char_start" in chunks[0]
    assert "char_end" in chunks[0]
    assert chunks[0]["char_start"] == 0


def test_chunk_text_token_count_present():
    text = "Some sample text for token counting purposes here."
    chunks = _chunk_text(text, chunk_size=500, overlap=0)
    assert "token_count" in chunks[0]
    assert chunks[0]["token_count"] == _estimate_tokens(chunks[0]["content"])


def test_chunk_text_empty_string_returns_no_chunks():
    assert _chunk_text("", chunk_size=500, overlap=50) == []


def test_chunk_text_whitespace_only_returns_no_chunks():
    assert _chunk_text("   \n\t  ", chunk_size=500, overlap=50) == []


# ── chunk_pages ───────────────────────────────────────────────────────────────

def test_chunk_pages_assigns_correct_page_numbers():
    pages = [
        {"page": 1, "text": "Content from page one. " * 5},
        {"page": 2, "text": "Content from page two. " * 5},
    ]
    chunks = chunk_pages(pages, chunk_size=50, overlap=10)

    page_numbers = {c["page_number"] for c in chunks}
    assert 1 in page_numbers
    assert 2 in page_numbers


def test_chunk_pages_chunk_indices_are_sequential():
    pages = [
        {"page": 1, "text": "word " * 100},
        {"page": 2, "text": "word " * 100},
    ]
    chunks = chunk_pages(pages, chunk_size=50, overlap=10)

    indices = [c["chunk_index"] for c in chunks]
    assert indices == list(range(len(chunks))), "chunk_index must be sequential starting at 0"


def test_chunk_pages_empty_pages_returns_empty():
    assert chunk_pages([]) == []


def test_chunk_pages_single_empty_page_returns_empty():
    assert chunk_pages([{"page": 1, "text": ""}]) == []


def test_chunk_pages_preserves_all_fields():
    pages = [{"page": 3, "text": "Hello world this is a test sentence."}]
    chunks = chunk_pages(pages, chunk_size=500, overlap=0)

    assert len(chunks) == 1
    chunk = chunks[0]
    assert chunk["page_number"] == 3
    assert chunk["chunk_index"] == 0
    assert "content" in chunk
    assert "char_start" in chunk
    assert "char_end" in chunk
    assert "token_count" in chunk
