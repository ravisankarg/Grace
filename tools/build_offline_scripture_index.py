#!/usr/bin/env python3
"""Create the APK-bundled, laptop-built EmbeddingGemma scripture index.

The generated vectors must be made with the exact Q8 GGUF embedded model the
phone uses for queries. This script deliberately does not talk to the phone.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

WORDS_PER_CHUNK = 512
OVERLAP_WORDS = 48
DOCUMENT_PREFIX = "title: {book} | text: "
ROOT = Path(__file__).resolve().parents[1]
BOOKS = ROOT / "app/src/main/assets/books"
OUTPUT = ROOT / "app/src/main/assets/indexes/scripture-index-v3.jsonl"


def extract_pages(pdf: Path) -> list[str]:
    """Use Poppler page-by-page so each retrieved passage has a real citation."""
    info = subprocess.run(["pdfinfo", str(pdf)], check=True, capture_output=True, text=True).stdout
    pages = next(int(line.split(":", 1)[1]) for line in info.splitlines() if line.startswith("Pages:"))
    result = []
    for page in range(1, pages + 1):
        text = subprocess.run(
            ["pdftotext", "-f", str(page), "-l", str(page), "-layout", str(pdf), "-"],
            check=True, capture_output=True, text=True,
        ).stdout
        result.append(" ".join(text.split()))
    return result


def chunks(text: str) -> list[str]:
    words = text.split()
    if len(words) < 24:
        return []
    step = WORDS_PER_CHUNK - OVERLAP_WORDS
    result = []
    for start in range(0, len(words), step):
        selected = words[start:start + WORDS_PER_CHUNK]
        if len(selected) < 24:
            break
        result.append(" ".join(selected))
        if start + WORDS_PER_CHUNK >= len(words):
            break
    return result


def rows() -> list[dict[str, object]]:
    output = []
    for pdf in sorted(BOOKS.glob("*.pdf")):
        book = pdf.stem.replace("-", " ")
        for page_number, page in enumerate(extract_pages(pdf), start=1):
            for text in chunks(page):
                output.append({"book": book, "page": page_number, "text": text})
    return output


def build_encoder(build_dir: Path, llama_src: Path) -> Path:
    cmake = Path(os.environ.get("CMAKE", "/home/ravi/AG/Android_SDK/android-sdk/cmake/3.22.1/bin/cmake"))
    if not cmake.is_file():
        cmake = Path(shutil.which("cmake") or "")
    if not cmake.is_file():
        raise RuntimeError("CMake was not found; set CMAKE to a CMake executable")
    source = ROOT / "tools/offline_index"
    subprocess.run([str(cmake), "-S", str(source), "-B", str(build_dir), f"-DLLAMA_SRC={llama_src}"], check=True)
    subprocess.run([str(cmake), "--build", str(build_dir), "--target", "grace_offline_embedding", "-j", "8"], check=True)
    executable = build_dir / "grace_offline_embedding"
    if not executable.is_file():
        raise RuntimeError(f"Encoder was not built at {executable}")
    return executable


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path, help="Exact phone EmbeddingGemma Q8 GGUF")
    parser.add_argument("--llama-src", type=Path, default=Path("/home/ravi/codex/Ask_Galaxy/third_party/llama.cpp"))
    parser.add_argument("--build-dir", type=Path, default=Path(tempfile.gettempdir()) / "grace-offline-embedding-build")
    parser.add_argument("--work-dir", type=Path, default=Path(tempfile.gettempdir()) / "grace-scripture-index-work")
    args = parser.parse_args()
    if not args.model.is_file():
        raise SystemExit(f"Missing GGUF model: {args.model}")
    if not args.llama_src.is_dir():
        raise SystemExit(f"Missing llama.cpp source: {args.llama_src}")

    passages = rows()
    if not passages:
        raise SystemExit("No PDF passages were extracted")
    print(f"Embedding {len(passages)} passages: {WORDS_PER_CHUNK} words, {OVERLAP_WORDS}-word overlap", flush=True)
    encoder = build_encoder(args.build_dir, args.llama_src)
    args.work_dir.mkdir(parents=True, exist_ok=True)
    request_file = args.work_dir / "remaining.tsv"
    vectors_file = args.work_dir / "vectors.tsv.part"
    completed = sum(1 for _ in vectors_file.open(encoding="utf-8")) if vectors_file.is_file() else 0
    if completed > len(passages):
        raise SystemExit(f"Resume file has {completed} vectors for {len(passages)} passages; delete {vectors_file} and retry")
    with request_file.open("w", encoding="utf-8") as request:
        for index, row in enumerate(passages[completed:], start=completed):
            text = DOCUMENT_PREFIX.format(book=row["book"]) + str(row["text"])
            request.write(f"{index}\t{base64.b64encode(text.encode()).decode()}\n")
    if completed < len(passages):
        process = subprocess.run([str(encoder), str(args.model), str(request_file), str(vectors_file)])
        if process.returncode:
            now = sum(1 for _ in vectors_file.open(encoding="utf-8")) if vectors_file.is_file() else 0
            raise SystemExit(f"Embedding encoder stopped after {now} of {len(passages)} passages; rerun this command to resume")
    if not vectors_file.is_file():
        raise SystemExit("Embedding encoder failed")
    vectors = {}
    for line in vectors_file.read_text(encoding="utf-8").splitlines():
        index, vector = line.split("\t", 1)
        vectors[int(index)] = vector
    if len(vectors) != len(passages):
        raise SystemExit(f"Encoder returned {len(vectors)} vectors for {len(passages)} passages")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8") as output:
        for index, row in enumerate(passages):
            row["vector"] = vectors[index]
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"Wrote {len(passages)} passages to {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
