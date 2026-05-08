"""
ContextFlow Embedding Worker

Exposes health/metrics endpoints via FastAPI and runs a Kafka consumer
in a background thread that processes document.uploaded events end-to-end:
S3 download → text extraction → sliding-window chunking → embedding → pgvector write.
"""

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_fastapi_instrumentator import Instrumentator

from api.embed_router import router as embed_router
from consumer.kafka_consumer import start_consumer_thread, stop_consumer
from embeddings.embedder import load_model

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)


def _configure_tracing() -> None:
    otlp_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
    if not otlp_endpoint:
        return

    resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "embedding-worker")})
    provider = TracerProvider(resource=resource)
    provider.add_span_processor(
        BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint))
    )
    trace.set_tracer_provider(provider)
    logger.info("OpenTelemetry tracing configured → %s", otlp_endpoint)


@asynccontextmanager
async def lifespan(app: FastAPI):
    _configure_tracing()
    load_model()
    consumer_thread = start_consumer_thread()
    logger.info("Embedding worker ready.")

    yield

    stop_consumer()
    consumer_thread.join(timeout=10)
    logger.info("Embedding worker stopped.")


app = FastAPI(title="ContextFlow Embedding Worker", lifespan=lifespan)

# Auto-instrument FastAPI routes with OTEL spans
FastAPIInstrumentor.instrument_app(app)

# Expose /metrics endpoint for Prometheus scraping
Instrumentator().instrument(app).expose(app)

app.include_router(embed_router)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/ready")
def ready():
    """Kubernetes readiness probe — confirm model is loaded."""
    from embeddings.embedder import _model
    if _model is None:
        from fastapi import HTTPException
        raise HTTPException(status_code=503, detail="Model not loaded")
    return {"status": "ready"}
