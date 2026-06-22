<div align="center">

# Semantic Code Search

**Offline semantic code search for JetBrains Rider.**

Ask in plain English — *"where is the auth token refreshed?"* — and jump straight to the
code that answers it. Models, index, and search all run locally. No server, no cloud, nothing leaves your machine.

[![Build](https://github.com/limam-B/semantic-code-search/actions/workflows/build.yml/badge.svg)](https://github.com/limam-B/semantic-code-search/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/limam-B/semantic-code-search?sort=semver)](https://github.com/limam-B/semantic-code-search/releases/latest)
![Rider](https://img.shields.io/badge/Rider-2026.1%2B-000?logo=rider&logoColor=white)
![C#](https://img.shields.io/badge/language-C%23-178600)

</div>

---

## Contents

1. [Requirements](#requirements)
2. [Download the models](#download-the-models)
3. [Install the plugin](#install-the-plugin)
4. [Build from source](#build-from-source)
5. [Usage](#usage)
6. [Settings](#settings)
7. [How it works](#how-it-works)
8. [Limitations](#limitations)
9. [Development](#development)

---

## Requirements

|            |                                                              |
| ---------- | ------------------------------------------------------------ |
| **Rider**  | 2026.1.2 (build 261) or newer                                |
| **Models** | An embedding model in ONNX (required); a reranker (optional) |
| **GPU**    | Optional — uses CUDA when present, otherwise runs on CPU     |

---

## Download the models

The plugin doesn't bundle models — you download them once and point the plugin at them. Every model is
**two files**: the ONNX weights (`model.onnx`) and its tokenizer (`tokenizer.json`).

The default location, created automatically on first launch:

```text
~/.semantic-code-search/models/
```

*`~` is your home folder: `C:\Users\<you>` on Windows, `/Users/<you>` on macOS, `/home/<you>` on Linux.*

Create one folder per model — **named exactly as in the table** — and drop the two files inside.

| Model                             | Role                     | Download                                                                                                                                                                                                              |
| --------------------------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **gte-modernbert-base**           | Embedder · *default*     | [model.onnx](https://huggingface.co/Alibaba-NLP/gte-modernbert-base/resolve/main/onnx/model.onnx) · [tokenizer.json](https://huggingface.co/Alibaba-NLP/gte-modernbert-base/resolve/main/tokenizer.json)             |
| **gte-reranker-modernbert-base**  | Reranker · *optional*    | [model.onnx](https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/main/onnx/model.onnx) · [tokenizer.json](https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/main/tokenizer.json) |
| **jina-embeddings-v2-base-code**  | Embedder · *alternative* | [model.onnx](https://huggingface.co/jinaai/jina-embeddings-v2-base-code/resolve/main/onnx/model.onnx) · [tokenizer.json](https://huggingface.co/jinaai/jina-embeddings-v2-base-code/resolve/main/tokenizer.json)     |

Final layout:

```text
~/.semantic-code-search/models/
├─ gte-modernbert-base/
│  ├─ model.onnx
│  └─ tokenizer.json
└─ gte-reranker-modernbert-base/      ← optional
   ├─ model.onnx
   └─ tokenizer.json
```

> [!TIP]
> The `onnx/` folder on Hugging Face also has smaller, quantized builds (`model_fp16.onnx`,
> `model_quantized.onnx`, and others). Any of them works — just save it as `model.onnx`.

> [!NOTE]
> The reranker is optional. Without it, search still runs (semantic + keyword, fused); it only skips
> the final re-scoring step. Toggle it in Settings.

---

## Install the plugin

Grab a zip from the [**Releases**](https://github.com/limam-B/semantic-code-search/releases) page —
each release ships two builds:

| Build           | Size     | Platforms                 | GPU                                  |
| --------------- | -------- | ------------------------- | ------------------------------------ |
| **`…-cpu.zip`** | ~110 MB  | Windows · Linux · macOS   | —                                    |
| **`…-gpu.zip`** | ~545 MB  | Windows · Linux           | CUDA 12.x + cuDNN 9.x (else CPU)     |

Pick **cpu** unless you specifically want GPU acceleration. Then, in Rider:

> **Settings → Plugins → ⚙ → Install Plugin from Disk** → choose the zip → restart.

---

## Build from source

> [!IMPORTANT]
> Requires **JDK 21** — Gradle 8.13 won't run on JDK 24+. The easiest route is to open the project in
> IntelliJ IDEA and run the `buildPlugin` task, which uses IDEA's bundled JDK 21.

From a terminal with `JAVA_HOME` pointing at a JDK 21:

```bash
./gradlew buildPlugin              # GPU build (default)
./gradlew buildPlugin -Pgpu=false  # smaller, CPU-only build
```

The zip lands in `build/distributions/`. The first build downloads the Rider SDK, so it can take a few
minutes. A local Rider install is only needed to launch a sandbox with `./gradlew runIde`.

---

## Usage

1. Run **Rebuild Semantic Code Index** (or **Index now** in Settings) to embed your code. It's a
   one-time cost — the index is saved, reused between sessions, and refreshed as you edit.
2. Press <kbd>Shift</kbd> + <kbd>&#92;</kbd> to open search on the **Semantic Code** tab. *(Or press
   <kbd>Shift</kbd> twice and switch to that tab. Rebind it under Settings → Keymap.)*
3. Type a question in plain English, then press <kbd>Enter</kbd> on a result to jump to that line.

> [!NOTE]
> The first search downloads a small tokenizer library once, then it runs fully offline.

---

## Settings

**Settings → Tools → Semantic Code Search**

| Section                 | What it controls                                                                       |
| ----------------------- | -------------------------------------------------------------------------------------- |
| **Retrieval**           | Toggle keyword (BM25) search and the reranker; set how many results to show            |
| **Embedder / Reranker** | Choose the model and the folder its files live in                                      |
| **Indexing scope**      | *Included folders* to narrow indexing (e.g. `src`); *Excluded folders* to skip subpaths (e.g. `tests`) |

By default the whole project is indexed; common build, cache, and version-control folders are always
skipped.

---

## How it works

```text
chunk  →  embed  →  retrieve (vectors + BM25, fused)  →  rerank  →  results
```

1. **Chunk** — split each C# file into methods and types, from the text. (The C# syntax tree lives in
   Rider's backend, out of reach for a frontend plugin.)
2. **Embed** — turn each chunk into a vector with the ONNX model.
3. **Retrieve** — blend semantic similarity (cosine over the vectors) with keyword search (BM25) via
   Reciprocal Rank Fusion.
4. **Rerank** — if the reranker is installed, re-score the top candidates with a cross-encoder.
5. **Show** — results land in their own Search Everywhere tab.

The index is a flat list of vectors held in memory and saved to `.semantic-code-search/index.bin` in
your project. No database — brute-force search is plenty fast for a single codebase.

---

## Limitations

- The chunker is text-based, not a real parser: it doesn't handle braces inside strings or comments,
  single-line method bodies, or expression-bodied members. Fine for method-level results.
- Results land on the right method, not always the exact line.
- C# only, for now.

---

## Development

```bash
./gradlew test
```

Logic tests (chunker, BM25, fusion, vector store) always run. The model-based tests load the real ONNX
models from `~/.semantic-code-search/models/` and skip themselves when the models aren't present, so the
build stays green without them.
