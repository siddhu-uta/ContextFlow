"""
API endpoint tests using FastAPI's TestClient.

Model loading and the Kafka consumer thread are mocked so these tests
run without GPU, a running Kafka broker, or a database connection.
"""

import numpy as np
import pytest
from fastapi.testclient import TestClient
from unittest.mock import MagicMock, patch

import embeddings.embedder as embedder_module


@pytest.fixture
def client():
    """
    Start the FastAPI app with startup/shutdown side effects mocked out.
    The embedding model is stubbed with a MagicMock that returns realistic vectors.
    """
    mock_model = MagicMock()
    mock_model.encode.return_value = np.zeros((1, 384), dtype=np.float32)
    embedder_module._model = mock_model

    with patch("main.load_model"), \
         patch("main.start_consumer_thread", return_value=MagicMock()), \
         patch("main.stop_consumer"):
        from main import app
        with TestClient(app, raise_server_exceptions=True) as c:
            yield c

    embedder_module._model = None


# ── /health ───────────────────────────────────────────────────────────────────

def test_health_returns_ok(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


# ── /ready ────────────────────────────────────────────────────────────────────

def test_ready_model_loaded_returns_ready(client):
    response = client.get("/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_ready_model_not_loaded_returns_503(client):
    original = embedder_module._model
    embedder_module._model = None
    try:
        response = client.get("/ready")
        assert response.status_code == 503
    finally:
        embedder_module._model = original


# ── /embed ────────────────────────────────────────────────────────────────────

def test_embed_valid_text_returns_vector(client):
    response = client.post("/embed", json={"text": "What is a vector database?"})
    assert response.status_code == 200
    body = response.json()
    assert "embedding" in body
    assert isinstance(body["embedding"], list)
    assert len(body["embedding"]) == 384


def test_embed_returns_floats(client):
    response = client.post("/embed", json={"text": "Hello"})
    assert response.status_code == 200
    assert all(isinstance(v, float) for v in response.json()["embedding"])


def test_embed_missing_text_field_returns_422(client):
    response = client.post("/embed", json={})
    assert response.status_code == 422


def test_embed_empty_text_returns_vector(client):
    # Empty string is valid input — the model handles it
    response = client.post("/embed", json={"text": ""})
    assert response.status_code == 200


# ── /metrics ─────────────────────────────────────────────────────────────────

def test_metrics_endpoint_exists(client):
    response = client.get("/metrics")
    assert response.status_code == 200
    assert "http_requests_total" in response.text or "python_info" in response.text
