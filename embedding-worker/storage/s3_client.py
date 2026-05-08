import tempfile
import logging
from pathlib import Path

import boto3
from botocore.config import Config

from config.settings import settings

logger = logging.getLogger(__name__)


def _make_client():
    return boto3.client(
        "s3",
        endpoint_url=settings.s3_endpoint_url,
        region_name=settings.s3_region,
        aws_access_key_id=settings.aws_access_key_id,
        aws_secret_access_key=settings.aws_secret_access_key,
        config=Config(signature_version="s3v4"),
    )


def download_document(s3_key: str) -> str:
    """Downloads a document from S3 to a temp file. Returns the temp file path."""
    suffix = Path(s3_key).suffix or ".bin"
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)

    try:
        client = _make_client()
        client.download_fileobj(settings.s3_bucket, s3_key, tmp)
        tmp.flush()
        logger.info("Downloaded s3://%s/%s -> %s", settings.s3_bucket, s3_key, tmp.name)
        return tmp.name
    finally:
        tmp.close()
