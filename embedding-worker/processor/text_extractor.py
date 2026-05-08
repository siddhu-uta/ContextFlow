import logging
from pathlib import Path

logger = logging.getLogger(__name__)

# Each page is {"page": int, "text": str}
PageText = dict


def extract(file_path: str, content_type: str) -> list[PageText]:
    """Dispatches to the correct extractor based on content_type."""
    if content_type == "application/pdf":
        return _extract_pdf(file_path)
    elif content_type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
        return _extract_docx(file_path)
    elif content_type == "text/plain":
        return _extract_txt(file_path)
    else:
        raise ValueError(f"Unsupported content type for extraction: {content_type}")


def _extract_pdf(file_path: str) -> list[PageText]:
    from pypdf import PdfReader

    reader = PdfReader(file_path)
    pages = []
    for i, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        text = text.strip()
        if text:
            pages.append({"page": i, "text": text})

    logger.info("Extracted %d non-empty pages from PDF %s", len(pages), Path(file_path).name)
    return pages


def _extract_docx(file_path: str) -> list[PageText]:
    from docx import Document

    doc = Document(file_path)
    full_text = "\n".join(
        para.text for para in doc.paragraphs if para.text.strip()
    )

    if not full_text.strip():
        return []

    # DOCX has no reliable page boundary info — treat as single page
    return [{"page": 1, "text": full_text.strip()}]


def _extract_txt(file_path: str) -> list[PageText]:
    with open(file_path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read().strip()

    if not text:
        return []

    return [{"page": 1, "text": text}]
