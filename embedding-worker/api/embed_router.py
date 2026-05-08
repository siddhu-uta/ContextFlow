"""
Synchronous embedding endpoint used by the Query Service at query time.

The query must be embedded with the same model used at indexing time (all-MiniLM-L6-v2)
so that query vectors and document chunk vectors live in the same vector space.
Running in an asyncio executor prevents the CPU-bound encoding from blocking the event loop.
"""

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter(tags=["embed"])


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    embedding: list[float]
    dimension: int


@router.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest) -> EmbedResponse:
    from embeddings.embedder import _model

    if _model is None:
        raise HTTPException(status_code=503, detail="Embedding model not loaded yet")

    loop = asyncio.get_event_loop()
    embedding: list[float] = await loop.run_in_executor(
        None,
        lambda: _model.encode(
            [request.text], convert_to_numpy=True, show_progress_bar=False
        )[0].tolist(),
    )

    return EmbedResponse(embedding=embedding, dimension=len(embedding))
