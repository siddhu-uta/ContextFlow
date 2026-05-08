from datetime import datetime
from pydantic import BaseModel


class DocumentUploadedEvent(BaseModel):
    documentId: str
    jobId: str
    tenantId: str
    s3Key: str
    originalFilename: str
    contentType: str
    fileSizeBytes: int
    uploadedAt: datetime


class DocumentProcessedEvent(BaseModel):
    documentId: str
    jobId: str
    tenantId: str
    chunkCount: int
    status: str
    processedAt: datetime
