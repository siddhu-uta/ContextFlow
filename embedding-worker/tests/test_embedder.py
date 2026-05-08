"""
Unit tests for the embedding module.

The SentenceTransformer model is mocked throughout — these tests verify
the wiring between embed_chunks() and the model, not the model itself.
"""

import numpy as np
import pytest
from unittest.mock import MagicMock, patch

import embeddings.embedder as embedder_module
from embeddings.embedder import embed_chunks, load_model


EMBEDDING_DIM = 384


@pytest.fixture(autouse=True)
def reset_model():
    """Ensure _model is reset to None before and after each test."""
    embedder_module._model = None
    yield
    embedder_module._model = None


# ── load_model ────────────────────────────────────────────────────────────────

def test_load_model_sets_global_model():
    mock_instance = MagicMock()
    with patch("embeddings.embedder.SentenceTransformer", return_value=mock_instance) as mock_cls:
        load_model()
        mock_cls.assert_called_once()
        assert embedder_module._model is mock_instance


def test_load_model_called_twice_reloads():
    mock_instance_1 = MagicMock()
    mock_instance_2 = MagicMock()
    with patch("embeddings.embedder.SentenceTransformer",
               side_effect=[mock_instance_1, mock_instance_2]):
        load_model()
        load_model()
        assert embedder_module._model is mock_instance_2


# ── embed_chunks ──────────────────────────────────────────────────────────────

def test_embed_chunks_raises_when_model_not_loaded():
    chunks = [{"content": "some text"}]
    with pytest.raises(RuntimeError, match="Model not loaded"):
        embed_chunks(chunks)


def test_embed_chunks_adds_embedding_key():
    mock_model = MagicMock()
    mock_model.encode.return_value = np.zeros((2, EMBEDDING_DIM), dtype=np.float32)
    embedder_module._model = mock_model

    chunks = [{"content": "first chunk"}, {"content": "second chunk"}]
    result = embed_chunks(chunks)

    assert all("embedding" in c for c in result)


def test_embed_chunks_correct_dimension():
    mock_model = MagicMock()
    mock_model.encode.return_value = np.zeros((3, EMBEDDING_DIM), dtype=np.float32)
    embedder_module._model = mock_model

    chunks = [{"content": f"chunk {i}"} for i in range(3)]
    result = embed_chunks(chunks)

    for chunk in result:
        assert len(chunk["embedding"]) == EMBEDDING_DIM


def test_embed_chunks_embedding_is_list_of_floats():
    mock_model = MagicMock()
    mock_model.encode.return_value = np.random.rand(1, EMBEDDING_DIM).astype(np.float32)
    embedder_module._model = mock_model

    chunks = [{"content": "test text"}]
    result = embed_chunks(chunks)

    embedding = result[0]["embedding"]
    assert isinstance(embedding, list)
    assert all(isinstance(v, float) for v in embedding)


def test_embed_chunks_mutates_chunks_in_place():
    mock_model = MagicMock()
    mock_model.encode.return_value = np.zeros((1, EMBEDDING_DIM), dtype=np.float32)
    embedder_module._model = mock_model

    original = {"content": "some text", "char_start": 0}
    chunks = [original]
    result = embed_chunks(chunks)

    # Same object — mutated in place
    assert result[0] is original
    assert "embedding" in original


def test_embed_chunks_empty_list_returns_empty():
    mock_model = MagicMock()
    mock_model.encode.return_value = np.zeros((0, EMBEDDING_DIM), dtype=np.float32)
    embedder_module._model = mock_model

    result = embed_chunks([])
    assert result == []


def test_embed_chunks_passes_correct_texts_to_model():
    mock_model = MagicMock()
    mock_model.encode.return_value = np.zeros((2, EMBEDDING_DIM), dtype=np.float32)
    embedder_module._model = mock_model

    chunks = [{"content": "hello"}, {"content": "world"}]
    embed_chunks(chunks)

    call_args = mock_model.encode.call_args
    assert call_args[0][0] == ["hello", "world"]
