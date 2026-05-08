"""
Kafka consumer for document.uploaded events.

Runs in a background thread (started by FastAPI lifespan).
Manual offset commit after successful processing gives us at-least-once delivery —
combined with the idempotency guard in vector_store.py, reprocessing is safe.
"""

import json
import logging
import threading

from kafka import KafkaConsumer
from kafka.errors import KafkaError
from pydantic import ValidationError

from config.settings import settings
from models.events import DocumentUploadedEvent
from processor.pipeline import process

logger = logging.getLogger(__name__)

_stop_event = threading.Event()


def start_consumer_thread() -> threading.Thread:
    """Starts the Kafka consumer in a daemon thread. Returns the thread."""
    thread = threading.Thread(target=_consume_loop, name="kafka-consumer", daemon=True)
    thread.start()
    logger.info("Kafka consumer thread started.")
    return thread


def stop_consumer() -> None:
    _stop_event.set()


def _consume_loop() -> None:
    consumer = _make_consumer()
    logger.info(
        "Subscribing to topic '%s' (group=%s)",
        settings.kafka_topic_document_uploaded,
        settings.kafka_group_id,
    )

    try:
        while not _stop_event.is_set():
            records = consumer.poll(timeout_ms=1000)
            for _tp, messages in records.items():
                for message in messages:
                    _handle_message(consumer, message)
    except KafkaError as exc:
        logger.exception("Kafka consumer error: %s", exc)
    finally:
        consumer.close()
        logger.info("Kafka consumer closed.")


def _handle_message(consumer: KafkaConsumer, message) -> None:
    try:
        raw = message.value
        if isinstance(raw, bytes):
            raw = json.loads(raw.decode("utf-8"))

        event = DocumentUploadedEvent(**raw)
        logger.info(
            "Processing document %s (job=%s, tenant=%s)",
            event.documentId, event.jobId, event.tenantId,
        )

        chunk_count = process(event)
        logger.info("Completed document %s — %d chunks", event.documentId, chunk_count)

        # Commit only after successful processing
        consumer.commit()

    except ValidationError as exc:
        logger.error("Malformed event, skipping: %s", exc)
        consumer.commit()  # Don't retry corrupt messages

    except Exception as exc:
        logger.exception("Unhandled error for document — will NOT commit (will retry): %s", exc)
        # No commit → Kafka will redeliver on next poll after rebalance/restart


def _make_consumer() -> KafkaConsumer:
    return KafkaConsumer(
        settings.kafka_topic_document_uploaded,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=settings.kafka_group_id,
        auto_offset_reset=settings.kafka_auto_offset_reset,
        enable_auto_commit=False,   # Manual commit for at-least-once guarantee
        value_deserializer=lambda m: m,  # Raw bytes; we deserialize in _handle_message
        consumer_timeout_ms=-1,     # Block indefinitely in poll
        max_poll_interval_ms=600_000,  # 10 min — embedding can be slow for large docs
    )
