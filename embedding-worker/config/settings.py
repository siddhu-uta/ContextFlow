from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_group_id: str = "embedding-worker"
    kafka_topic_document_uploaded: str = "document.uploaded"
    kafka_topic_document_processed: str = "document.processed"
    kafka_auto_offset_reset: str = "earliest"

    # PostgreSQL
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "contextflow"
    db_user: str = "contextflow"
    db_password: str = "contextflow"
    db_schema: str = "ingestion"

    # S3 / LocalStack
    s3_endpoint_url: str = "http://localhost:4566"
    s3_bucket: str = "contextflow-documents"
    s3_region: str = "us-east-1"
    aws_access_key_id: str = "test"
    aws_secret_access_key: str = "test"

    # Embedding model
    embedding_model: str = "all-MiniLM-L6-v2"
    embedding_batch_size: int = 32

    # Chunking
    chunk_size_chars: int = 1000
    chunk_overlap_chars: int = 200

    # Worker
    worker_concurrency: int = 4

    @property
    def db_dsn(self) -> str:
        return (
            f"postgresql://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )


settings = Settings()
