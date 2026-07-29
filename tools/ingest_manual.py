#!/usr/bin/env python3
"""Convert the bundled PDF to page-aware Markdown and rebuild manual embeddings.

The existing MANUAL_PAGES and MANUAL_IMAGES rows are deliberately preserved.
Only MANUAL_CHUNKS (and its external-content FTS5 index) are replaced.
"""

from __future__ import annotations

import argparse
import io
import os
import re
import shutil
import sqlite3
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

import numpy as np
import onnxruntime as ort
from markitdown import MarkItDown
from pypdf import PdfReader, PdfWriter


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PDF = ROOT / "2021-vw-atlas.pdf"
DEFAULT_MARKDOWN = ROOT / "manual" / "2021-vw-atlas.md"
DEFAULT_DATABASE = ROOT / "app/src/main/assets/database/manuals.db"
DEFAULT_MODEL = ROOT / "app/src/main/assets/models/minilm-qint8-arm64.onnx"
DEFAULT_VOCAB = ROOT / "app/src/main/assets/models/minilm-vocab.txt"
ASSET_ROOT = ROOT / "app/src/main/assets/manual_assets"

MAX_TOKENS = 256
MAX_CHUNK_TOKENS = 224
EMBED_BATCH_SIZE = 24

PAGE_MARKER = re.compile(r"^<!-- atlas-page: (\d+) -->$")
MARKDOWN_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
SENTENCE = re.compile(r"(?<=[.!?])\s+")
FIGURE = re.compile(r"\bFig\.\s*(\d+)\b", re.IGNORECASE)
SPACE = re.compile(r"\s+")


@dataclass(frozen=True)
class ImageRow:
    image_id: int
    page_number: int
    asset_path: str
    thumbnail_path: str
    caption: str


@dataclass(frozen=True)
class Chunk:
    page_id: int
    page_number: int
    chunk_index: int
    section_title: str
    markdown: str


def arguments() -> argparse.Namespace:
    """Parse paths and conversion controls for the reproducible ingestion job."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", type=Path, default=DEFAULT_PDF)
    parser.add_argument("--markdown", type=Path, default=DEFAULT_MARKDOWN)
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--vocab", type=Path, default=DEFAULT_VOCAB)
    parser.add_argument(
        "--skip-convert",
        action="store_true",
        help="Reuse the existing page-aware Markdown file.",
    )
    return parser.parse_args()


def normalized_heading(value: str) -> str:
    """Normalize headings for robust comparison across PDF extraction variants."""
    value = unicodedata.normalize("NFKC", value)
    value = value.replace("⇒", " ").replace(" ", " ").replace("\u00a0", " ")
    value = re.sub(r"[^a-z0-9]+", " ", value.lower())
    return SPACE.sub(" ", value).strip()


def clean_markitdown(text: str) -> str:
    """Remove extraction noise without changing the manual's wording."""
    text = unicodedata.normalize("NFKC", text)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace("\u00a0", " ").replace(" ", " ")
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(
        r"(?i)\bIntroduction to the\s*\n\s*\n\s*subject\b",
        "Introduction to the subject",
        text,
    )
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def pdf_heading_levels(page: object) -> dict[str, int]:
    """Read heading levels from PDF font sizes retained outside MarkItDown."""
    levels: dict[str, int] = {}

    def visitor(
        text: str,
        _cm: object,
        _tm: object,
        _font: object,
        font_size: float,
    ) -> None:
        """Map visually prominent PDF text to semantic Markdown heading levels."""
        candidate = SPACE.sub(" ", text).strip()
        key = normalized_heading(candidate)
        if not key or len(candidate) > 100:
            return
        if font_size >= 22:
            levels[key] = 2
        elif font_size >= 17.5:
            levels.setdefault(key, 3)

    page.extract_text(visitor_text=visitor)
    return levels


def promote_headings(
    text: str,
    heading_levels: dict[str, int],
) -> str:
    """Restore PDF-derived heading levels in MarkItDown's page text."""
    output: list[str] = []
    for block in re.split(r"\n\s*\n", text):
        block = block.strip()
        if not block:
            continue
        level = heading_levels.get(normalized_heading(block))
        if level:
            heading = SPACE.sub(" ", block).strip()
            output.append(f"{'#' * level} {heading}")
        else:
            output.append(block)
    return "\n\n".join(output)


def page_pdf(reader: PdfReader, index: int) -> io.BytesIO:
    """Serialize one PDF page for deterministic page-aware conversion."""
    writer = PdfWriter()
    writer.add_page(reader.pages[index])
    stream = io.BytesIO()
    writer.write(stream)
    stream.seek(0)
    return stream


def read_database_metadata(
    database: Path,
) -> tuple[dict[int, int], dict[int, list[ImageRow]]]:
    """Load stable page IDs and image metadata that ingestion must preserve."""
    connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
    try:
        pages = {
            number: page_id
            for page_id, number in connection.execute(
                "SELECT id,pageNumber FROM MANUAL_PAGES ORDER BY pageNumber"
            )
        }
        images: dict[int, list[ImageRow]] = defaultdict(list)
        for row in connection.execute(
            "SELECT i.id,p.pageNumber,i.assetPath,i.thumbnailPath,i.caption "
            "FROM MANUAL_IMAGES i JOIN MANUAL_PAGES p ON p.id=i.pageId "
            "ORDER BY p.pageNumber,i.id"
        ):
            image = ImageRow(*row)
            images[image.page_number].append(image)
        return pages, images
    finally:
        connection.close()


def image_markdown(images: Sequence[ImageRow]) -> str:
    """Represent database images as durable manual-asset Markdown links."""
    if not images:
        return ""
    lines = ["#### Manual figures"]
    for image in images:
        caption = image.caption.strip() or f"Manual image {image.image_id}"
        lines.append(
            f"<!-- atlas-image-id: {image.image_id} -->\n"
            f"![{caption}](manual-asset://{image.asset_path})"
        )
    return "\n\n".join(lines)


def convert_pdf(
    pdf: Path,
    markdown_path: Path,
    page_ids: dict[int, int],
    images: dict[int, list[ImageRow]],
) -> None:
    """Convert each page independently and write an atomically replaced Markdown file."""
    reader = PdfReader(pdf)
    if len(reader.pages) != len(page_ids):
        raise ValueError(
            f"PDF has {len(reader.pages)} pages, database has {len(page_ids)}"
        )
    converter = MarkItDown(enable_plugins=False)
    markdown_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = markdown_path.with_suffix(markdown_path.suffix + ".part")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        output.write("# 2019 Volkswagen Atlas 3.6L Owner's Manual\n\n")
        output.write(
            "<!-- Generated page-by-page with Microsoft MarkItDown 0.1.6. "
            "Do not remove atlas-page or atlas-image markers. -->\n"
        )
        for index in range(len(reader.pages)):
            page_number = index + 1
            result = converter.convert_stream(
                page_pdf(reader, index), file_extension=".pdf"
            )
            body = promote_headings(
                clean_markitdown(result.text_content),
                pdf_heading_levels(reader.pages[index]),
            )
            figures = image_markdown(images[page_number])
            output.write(f"\n\n<!-- atlas-page: {page_number} -->\n\n")
            output.write(f"# Page {page_number}\n\n")
            output.write(body or "_No extractable text on this page._")
            if figures:
                output.write("\n\n" + figures)
            if page_number % 50 == 0 or page_number == len(reader.pages):
                print(f"Converted {page_number}/{len(reader.pages)} pages")
    os.replace(temporary, markdown_path)


def parse_page_markdown(markdown_path: Path) -> dict[int, str]:
    """Split generated Markdown back into pages using durable HTML markers."""
    pages: dict[int, list[str]] = defaultdict(list)
    current: int | None = None
    for line in markdown_path.read_text(encoding="utf-8").splitlines():
        marker = PAGE_MARKER.match(line)
        if marker:
            current = int(marker.group(1))
            continue
        if current is None or line == f"# Page {current}":
            continue
        pages[current].append(line)
    return {page: "\n".join(lines).strip() for page, lines in pages.items()}


class WordPieceTokenizer:
    """Minimal tokenizer matching the bundled MiniLM WordPiece vocabulary."""

    def __init__(self, vocabulary_path: Path) -> None:
        """Load vocabulary IDs and required BERT control tokens."""
        tokens = vocabulary_path.read_text(encoding="utf-8").splitlines()
        self.vocabulary = {token: index for index, token in enumerate(tokens)}
        self.unknown = self.vocabulary["[UNK]"]
        self.cls = self.vocabulary["[CLS]"]
        self.sep = self.vocabulary["[SEP]"]
        self.pad = self.vocabulary["[PAD]"]

    def token_ids(self, text: str, maximum: int | None = None) -> list[int]:
        """Tokenize text greedily, optionally reserving space for the SEP token."""
        normalized = unicodedata.normalize("NFD", text)
        normalized = "".join(
            char for char in normalized if unicodedata.category(char) != "Mn"
        ).lower()
        pieces = [self.cls]
        for token in re.findall(r"[^\W_]+|\d+|[^\s\w]", normalized, re.UNICODE):
            if maximum is not None and len(pieces) >= maximum - 1:
                break
            whole = self.vocabulary.get(token)
            if whole is not None:
                pieces.append(whole)
                continue
            if len(token) > 100:
                pieces.append(self.unknown)
                continue
            start = 0
            sub_tokens: list[int] = []
            while start < len(token):
                found: int | None = None
                found_end = start
                for end in range(len(token), start, -1):
                    candidate = token[start:end]
                    if start:
                        candidate = "##" + candidate
                    if candidate in self.vocabulary:
                        found = self.vocabulary[candidate]
                        found_end = end
                        break
                if found is None:
                    sub_tokens = [self.unknown]
                    break
                sub_tokens.append(found)
                start = found_end
            if maximum is None:
                pieces.extend(sub_tokens)
            else:
                pieces.extend(sub_tokens[: maximum - 1 - len(pieces)])
        pieces.append(self.sep)
        return pieces

    def token_count(self, text: str) -> int:
        """Return the exact token count used for chunk budgeting."""
        return len(self.token_ids(text))

    def encode(self, text: str) -> tuple[np.ndarray, np.ndarray]:
        """Return padded token IDs and attention mask for ONNX inference."""
        pieces = self.token_ids(text, MAX_TOKENS)
        ids = np.full(MAX_TOKENS, self.pad, dtype=np.int64)
        mask = np.zeros(MAX_TOKENS, dtype=np.int64)
        ids[: len(pieces)] = pieces
        mask[: len(pieces)] = 1
        return ids, mask


@dataclass(frozen=True)
class SectionContext:
    """Semantic headings carried into each standalone embedded chunk."""

    chapter: str = ""
    topic: str = ""
    qualifier: str = ""

    @property
    def title(self) -> str:
        """Return the most specific heading suitable for retrieval display."""
        return self.topic or self.chapter

    def render(self, body: str) -> str:
        """Render inherited headings with a body into self-contained Markdown."""
        headings: list[str] = []
        if self.chapter:
            headings.append(f"## {self.chapter}")
        if self.topic and self.topic != self.chapter:
            headings.append(f"### {self.topic}")
        if self.qualifier:
            headings.append(f"#### {self.qualifier}")
        return "\n\n".join([*headings, body]).strip()


CALLOUT_HEADINGS = {"warning", "note", "caution", "danger"}
QUALIFIER_PREFIXES = ("applicable only",)
SYNTHETIC_FIGURES = "manual figures"
INTRODUCTION = "introduction to the subject"


def standalone_parent(block: str) -> str | None:
    """Recognize short visual parent headings omitted by PDF font extraction."""
    candidate = SPACE.sub(" ", block).strip()
    words = candidate.split()
    if (
        "\n" in block
        or not 1 <= len(words) <= 6
        or not 4 <= len(candidate) <= 60
        or not any(char.isupper() for char in candidate)
        or re.search(r"[.!?;:,⇒]", candidate)
    ):
        return None
    return candidate


def document_chapters(
    pages: dict[int, str],
) -> dict[tuple[int, int], tuple[str, bool]]:
    """Locate semantic parent headings without relying on hardcoded topics."""
    headings: list[tuple[int, int, str]] = []
    parents: dict[tuple[int, int], tuple[str, bool]] = {}
    for page_number in sorted(pages):
        page_blocks = re.split(r"\n\s*\n", pages[page_number])
        for block_index, block in enumerate(page_blocks):
            match = MARKDOWN_HEADING.match(block.strip())
            if match and normalized_heading(match.group(2)) != SYNTHETIC_FIGURES:
                headings.append((page_number, block_index, match.group(2).strip()))
            if block_index + 1 >= len(page_blocks):
                continue
            next_heading = MARKDOWN_HEADING.match(page_blocks[block_index + 1].strip())
            if next_heading:
                next_normalized = normalized_heading(next_heading.group(2))
                if (
                    match
                    and len(match.group(1)) == 2
                    and len(next_heading.group(1)) == 2
                    and next_normalized != SYNTHETIC_FIGURES
                ):
                    parents[(page_number, block_index)] = (
                        match.group(2).strip(),
                        True,
                    )
                elif not match:
                    title = standalone_parent(block)
                    if title and next_normalized == INTRODUCTION:
                        parents[(page_number, block_index)] = (title, True)

    for index, (_, _, heading) in enumerate(headings):
        if index == 0 or normalized_heading(heading) != INTRODUCTION:
            continue
        previous = headings[index - 1]
        normalized = normalized_heading(previous[2])
        if normalized not in CALLOUT_HEADINGS and not normalized.startswith(
            QUALIFIER_PREFIXES
        ):
            parents[(previous[0], previous[1])] = (previous[2], True)
    return parents


def split_words_to_fit(
    text: str,
    context: SectionContext,
    tokenizer: WordPieceTokenizer,
) -> list[str]:
    """Split an oversized sentence at word boundaries as a last resort."""
    pieces: list[str] = []
    current: list[str] = []
    for word in text.split():
        candidate = " ".join([*current, word])
        if (
            current
            and tokenizer.token_count(context.render(candidate)) > MAX_CHUNK_TOKENS
        ):
            pieces.append(" ".join(current))
            current = [word]
        else:
            current.append(word)
    if current:
        pieces.append(" ".join(current))
    return pieces


def split_block_to_fit(
    block: str,
    context: SectionContext,
    tokenizer: WordPieceTokenizer,
) -> list[str]:
    """Prefer sentence boundaries when a Markdown block exceeds the token budget."""
    if tokenizer.token_count(context.render(block)) <= MAX_CHUNK_TOKENS:
        return [block]
    pieces: list[str] = []
    current = ""
    for sentence in SENTENCE.split(SPACE.sub(" ", block).strip()):
        candidate = f"{current} {sentence}".strip()
        if (
            current
            and tokenizer.token_count(context.render(candidate)) > MAX_CHUNK_TOKENS
        ):
            pieces.append(current)
            current = ""
        if tokenizer.token_count(context.render(sentence)) > MAX_CHUNK_TOKENS:
            pieces.extend(split_words_to_fit(sentence, context, tokenizer))
        else:
            current = sentence if not current else f"{current} {sentence}"
    if current:
        pieces.append(current)
    return pieces


def semantic_blocks_for_page(
    page_number: int,
    page_markdown: str,
    chapter_headings: dict[tuple[int, int], tuple[str, bool]],
    chapter: str,
    topic: str,
    tokenizer: WordPieceTokenizer,
) -> tuple[list[tuple[SectionContext, str]], str, str]:
    """Convert one page into token-safe blocks while updating section state."""
    blocks: list[tuple[SectionContext, str]] = []
    qualifier = ""
    callout = ""
    figures = False

    for block_index, raw_block in enumerate(re.split(r"\n\s*\n", page_markdown)):
        block = raw_block.strip()
        if not block:
            continue
        parent_info = chapter_headings.get((page_number, block_index))
        parent = parent_info[0] if parent_info else ""
        strong_parent = parent_info[1] if parent_info else False
        heading = MARKDOWN_HEADING.match(block)
        use_parent = bool(parent and strong_parent)

        if use_parent and not heading:
            chapter, topic = parent, ""
            qualifier = callout = ""
            figures = False
            continue
        if heading:
            title = heading.group(2).strip()
            normalized = normalized_heading(title)
            if normalized == SYNTHETIC_FIGURES:
                figures, qualifier, callout = True, "", ""
            elif use_parent:
                chapter, topic = parent, ""
                figures, qualifier, callout = False, "", ""
            elif normalized == INTRODUCTION:
                topic = title
                figures, qualifier, callout = False, "", ""
            elif normalized in CALLOUT_HEADINGS:
                callout, figures = title, False
            elif normalized.startswith(QUALIFIER_PREFIXES):
                qualifier, figures = title, False
            else:
                topic = title
                figures, qualifier, callout = False, "", ""
            continue

        effective_qualifier = qualifier
        if figures:
            effective_qualifier = "Figures and diagrams"
        elif callout:
            effective_qualifier = callout
        context = SectionContext(chapter, topic, effective_qualifier)
        for piece in split_block_to_fit(block, context, tokenizer):
            blocks.append((context, piece))
    return blocks, chapter, topic


def pack_page_blocks(
    blocks: Sequence[tuple[SectionContext, str]],
    fallback_context: SectionContext,
    tokenizer: WordPieceTokenizer,
) -> list[tuple[SectionContext, str]]:
    """Pack adjacent blocks that share context without crossing the token limit."""
    packed: list[tuple[SectionContext, str]] = []
    current_context: SectionContext | None = None
    current_blocks: list[str] = []

    for context, block in blocks:
        candidate = "\n\n".join([*current_blocks, block])
        exceeds_limit = (
            current_blocks
            and tokenizer.token_count(context.render(candidate)) > MAX_CHUNK_TOKENS
        )
        if current_blocks and (context != current_context or exceeds_limit):
            assert current_context is not None
            packed.append((current_context, "\n\n".join(current_blocks)))
            current_blocks = []
        if not current_blocks:
            current_context = context
        current_blocks.append(block)

    if current_context is not None and current_blocks:
        packed.append((current_context, "\n\n".join(current_blocks)))
    if not packed:
        packed.append((fallback_context, "_No extractable text on this page._"))
    return packed


def append_page_chunks(
    chunks: list[Chunk],
    page_id: int,
    page_number: int,
    packed: Sequence[tuple[SectionContext, str]],
    tokenizer: WordPieceTokenizer,
) -> None:
    """Validate and append the final database chunks for one manual page."""
    for index, (context, body) in enumerate(packed):
        markdown = context.render(body)
        token_count = tokenizer.token_count(markdown)
        if token_count > MAX_CHUNK_TOKENS:
            raise RuntimeError(
                f"Chunk exceeds token budget on page {page_number}: {token_count}"
            )
        chunks.append(Chunk(page_id, page_number, index, context.title, markdown))


def chunks_for_document(
    page_ids: dict[int, int],
    pages: dict[int, str],
    tokenizer: WordPieceTokenizer,
) -> list[Chunk]:
    """Chunk within semantic sections while carrying their context across pages."""
    chapter_headings = document_chapters(pages)
    chapter = ""
    topic = ""
    chunks: list[Chunk] = []
    for page_number in sorted(page_ids):
        blocks, chapter, topic = semantic_blocks_for_page(
            page_number,
            pages[page_number],
            chapter_headings,
            chapter,
            topic,
            tokenizer,
        )
        packed = pack_page_blocks(blocks, SectionContext(chapter, topic), tokenizer)
        append_page_chunks(
            chunks, page_ids[page_number], page_number, packed, tokenizer
        )
    return chunks


class MiniLmEmbedder:
    """CPU-bounded ONNX embedder using the same pooling as the Android app."""

    def __init__(
        self,
        model: Path,
        vocabulary: Path,
        tokenizer: WordPieceTokenizer | None = None,
    ) -> None:
        """Open an optimized CPU session and reuse the ingestion tokenizer."""
        options = ort.SessionOptions()
        options.intra_op_num_threads = max(1, (os.cpu_count() or 2) // 2)
        options.inter_op_num_threads = 1
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(
            str(model), sess_options=options, providers=["CPUExecutionProvider"]
        )
        self.tokenizer = tokenizer or WordPieceTokenizer(vocabulary)

    def embed(self, texts: Sequence[str]) -> Iterator[bytes]:
        """Yield normalized little-endian embeddings in bounded batches."""
        for start in range(0, len(texts), EMBED_BATCH_SIZE):
            batch = texts[start : start + EMBED_BATCH_SIZE]
            encoded = [self.tokenizer.encode(text) for text in batch]
            ids = np.stack([item[0] for item in encoded])
            mask = np.stack([item[1] for item in encoded])
            token_types = np.zeros_like(ids)
            hidden = self.session.run(
                None,
                {
                    "input_ids": ids,
                    "attention_mask": mask,
                    "token_type_ids": token_types,
                },
            )[0]
            weights = mask[:, :, None].astype(np.float32)
            pooled = (hidden * weights).sum(axis=1)
            pooled /= np.maximum(weights.sum(axis=1), 1.0)
            norms = np.linalg.norm(pooled, axis=1, keepdims=True)
            pooled /= np.maximum(norms, 1e-12)
            for vector in pooled.astype("<f4", copy=False):
                yield vector.tobytes()
            completed = min(start + len(batch), len(texts))
            if completed % 240 == 0 or completed == len(texts):
                print(f"Embedded {completed}/{len(texts)} chunks")


def validate_images(images: dict[int, list[ImageRow]]) -> None:
    """Fail before database mutation if any referenced image asset is absent."""
    missing: list[Path] = []
    for page_images in images.values():
        for image in page_images:
            for relative in (image.asset_path, image.thumbnail_path):
                path = ASSET_ROOT / relative
                if not path.is_file():
                    missing.append(path)
    if missing:
        raise FileNotFoundError(f"Missing manual image assets: {missing[:5]}")


def image_signature(database: Path) -> list[tuple]:
    """Capture image rows so text ingestion can prove they remain unchanged."""
    connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
    try:
        return list(
            connection.execute(
                "SELECT id,pageId,assetPath,thumbnailPath,caption "
                "FROM MANUAL_IMAGES ORDER BY id"
            )
        )
    finally:
        connection.close()


def rebuild_database(
    database: Path,
    chunks: Sequence[Chunk],
    embeddings: Iterable[bytes],
    original_images: list[tuple],
) -> None:
    """Atomically replace chunks, embeddings, and FTS data after validation."""
    temporary = database.with_suffix(database.suffix + ".part")
    shutil.copy2(database, temporary)
    connection = sqlite3.connect(temporary)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        with connection:
            connection.execute("DELETE FROM MANUAL_CHUNKS")
            connection.execute("DROP TABLE MANUAL_CHUNKS_FTS")
            connection.execute(
                "CREATE VIRTUAL TABLE MANUAL_CHUNKS_FTS USING fts5("
                "sectionTitle,text,content='MANUAL_CHUNKS',content_rowid='id',"
                "tokenize='porter unicode61 remove_diacritics 2',"
                "prefix='2 3 4')"
            )
            connection.executemany(
                "INSERT INTO MANUAL_CHUNKS("
                "pageId,chunkIndex,sectionTitle,text,embedding"
                ") VALUES(?,?,?,?,?)",
                (
                    (
                        chunk.page_id,
                        chunk.chunk_index,
                        chunk.section_title,
                        chunk.markdown,
                        embedding,
                    )
                    for chunk, embedding in zip(chunks, embeddings, strict=True)
                ),
            )
            connection.execute(
                "INSERT INTO MANUAL_CHUNKS_FTS(MANUAL_CHUNKS_FTS) VALUES('rebuild')"
            )
        connection.execute("VACUUM")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"SQLite integrity check failed: {integrity}")
        count = connection.execute("SELECT count(*) FROM MANUAL_CHUNKS").fetchone()[0]
        fts_count = connection.execute(
            "SELECT count(*) FROM MANUAL_CHUNKS_FTS"
        ).fetchone()[0]
        if count != len(chunks) or fts_count != len(chunks):
            raise RuntimeError(
                f"Chunk/FTS mismatch: chunks={count}, fts={fts_count}, "
                f"expected={len(chunks)}"
            )
    finally:
        connection.close()
    if image_signature(temporary) != original_images:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("MANUAL_IMAGES changed during text ingestion")
    os.replace(temporary, database)


def main() -> None:
    """Run conversion, chunking, embedding, and database replacement."""
    args = arguments()
    page_ids, images = read_database_metadata(args.database)
    validate_images(images)
    original_images = image_signature(args.database)

    if not args.skip_convert:
        convert_pdf(args.pdf, args.markdown, page_ids, images)
    pages = parse_page_markdown(args.markdown)
    if set(pages) != set(page_ids):
        raise ValueError(
            f"Markdown page map differs from database: "
            f"markdown={len(pages)}, database={len(page_ids)}"
        )

    tokenizer = WordPieceTokenizer(args.vocab)
    chunks = chunks_for_document(page_ids, pages, tokenizer)
    embedder = MiniLmEmbedder(args.model, args.vocab, tokenizer)
    embeddings = embedder.embed(
        [f"{chunk.section_title}\n{chunk.markdown}" for chunk in chunks]
    )
    rebuild_database(args.database, chunks, embeddings, original_images)

    figure_links = sum(len(FIGURE.findall(chunk.markdown)) for chunk in chunks)
    print(
        f"Done: pages={len(pages)}, chunks={len(chunks)}, "
        f"images={len(original_images)}, figure_mentions={figure_links}, "
        f"database_bytes={args.database.stat().st_size}"
    )


if __name__ == "__main__":
    main()
