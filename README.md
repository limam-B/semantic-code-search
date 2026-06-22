# Semantic Code Search

A Rider plugin for offline semantic code search. Ask a question in plain English, like
*"where is the auth token refreshed?"*, and jump straight to the code that answers it.
Everything runs locally — the models, the index, and the search — so no code leaves your machine.

Works on C# code. Built and tested against Rider 2026.1.2 (build 261).

## Contents

- [Requirements](#requirements)
- [1. Download the models](#1-download-the-models)
- [2. Install the plugin](#2-install-the-plugin)
- [3. Build from source](#3-build-from-source)
- [Usage](#usage)
- [Settings](#settings)
- [How it works](#how-it-works)
- [Limitations](#limitations)
- [Development](#development)

## Requirements

- JetBrains Rider 2026.1.2 (build 261) or newer.
- An embedding model in ONNX format (see below). A reranker model is optional.
- A GPU is optional. With CUDA the models run on the GPU; otherwise they run on the CPU.

## 1. Download the models

The plugin does not bundle any models. You download them once and point the plugin at them.
Each model is two files: the ONNX weights (`model.onnx`) and its tokenizer (`tokenizer.json`).

By default the plugin looks here, and creates the folder on first launch:

```
%USERPROFILE%\.semantic-code-search\models\
```

Inside it, make one subfolder per model, named exactly as shown below, and put the two files in.
(You can change this location in Settings if you'd rather keep the models elsewhere.)

### Embedding model (required)

**gte-modernbert-base** — the default. Repo: <https://huggingface.co/Alibaba-NLP/gte-modernbert-base>

Save into `gte-modernbert-base\`:
- `model.onnx` — <https://huggingface.co/Alibaba-NLP/gte-modernbert-base/resolve/main/onnx/model.onnx>
- `tokenizer.json` — <https://huggingface.co/Alibaba-NLP/gte-modernbert-base/resolve/main/tokenizer.json>

### Reranker model (optional)

Re-scores the top results for better ordering. If you skip it, search still works (it just uses
semantic + keyword search without the final rerank). Turn it on or off in Settings.

**gte-reranker-modernbert-base** — Repo: <https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base>

Save into `gte-reranker-modernbert-base\`:
- `model.onnx` — <https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/main/onnx/model.onnx>
- `tokenizer.json` — <https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/main/tokenizer.json>

### Alternative embedding model

You can use this in place of gte-modernbert-base (select it in Settings).

**jina-embeddings-v2-base-code** — Repo: <https://huggingface.co/jinaai/jina-embeddings-v2-base-code>

Save into `jina-embeddings-v2-base-code\`:
- `model.onnx` — <https://huggingface.co/jinaai/jina-embeddings-v2-base-code/resolve/main/onnx/model.onnx>
- `tokenizer.json` — <https://huggingface.co/jinaai/jina-embeddings-v2-base-code/resolve/main/tokenizer.json>

> The `onnx/` folder on Hugging Face also has smaller, quantized versions (`model_fp16.onnx`,
> `model_quantized.onnx`, and others). Any of them works — just save it in the model folder as
> `model.onnx`.

When you're done it should look like this:

```
.semantic-code-search\models\
  gte-modernbert-base\
    model.onnx
    tokenizer.json
  gte-reranker-modernbert-base\        (optional)
    model.onnx
    tokenizer.json
```

## 2. Install the plugin

Grab a zip from the [Releases page](https://github.com/limam-B/semantic-code-search/releases).
Each release comes in two builds:

- **`...-cpu.zip`** (about 110 MB) — runs on Windows, Linux, and macOS. Use this unless you
  specifically want GPU acceleration.
- **`...-gpu.zip`** (about 545 MB) — Windows and Linux. Uses CUDA when CUDA 12.x and cuDNN 9.x are
  installed, and falls back to the CPU otherwise.

In Rider, go to **Settings → Plugins → ⚙ → Install Plugin from Disk**, choose the zip, and restart.

## 3. Build from source

Only needed if you'd rather build it yourself instead of using a release.

You need **JDK 21** (Gradle 8.13 will not run on JDK 24 or newer). The easiest route is to open the
project in IntelliJ IDEA and run the `buildPlugin` Gradle task, which uses IDEA's bundled JDK 21.
From a terminal, with `JAVA_HOME` set to a JDK 21:

```
./gradlew buildPlugin
```

The zip is written to `build/distributions/`. This builds the GPU version by default; add
`-Pgpu=false` for the smaller CPU-only build.

The first build downloads the Rider SDK, so it can take a few minutes. A local Rider installation is
only needed if you want to launch the plugin in a sandbox with `./gradlew runIde`.

## Usage

1. Open your project and run **Rebuild Semantic Code Index** (or click **Index now** in Settings).
   This embeds your code. It's a one-time cost — the index is saved and reused between sessions, and
   it updates itself as you edit files.
2. Press **`Shift+\`** to open search on the **Semantic Code** tab. (You can also press Shift twice
   and switch to that tab, or rebind the shortcut in Settings → Keymap.)
3. Type your question in plain English and press Enter on a result to jump to that line.

> The very first search downloads a small tokenizer library once. After that it works fully offline.

## Settings

Found under **Settings → Tools → Semantic Code Search**:

- **Retrieval** — turn the keyword (BM25) stage and the reranker on or off, and choose how many
  results to show.
- **Embedder / Reranker** — pick the model and tell the plugin which folder its files are in.
- **Indexing scope** — by default the whole project is indexed (common build, cache, and version
  control folders are always skipped). Use **Included folders** to restrict indexing to specific
  folders (for example `src`), and **Excluded folders** to skip subpaths (for example `tests`).

## How it works

Search is a short pipeline, and each stage can be swapped out:

1. **Chunk** — split each C# file into its methods and types. This is done from the text, because the
   C# syntax tree lives in Rider's backend and isn't available to a frontend plugin.
2. **Embed** — turn each chunk into a vector using the ONNX model.
3. **Retrieve** — combine semantic similarity (cosine distance over the vectors) with keyword search
   (BM25), then merge the two rankings with Reciprocal Rank Fusion.
4. **Rerank** — if the reranker model is present, re-score the top candidates with a cross-encoder for
   a sharper final order.
5. **Show** — results appear in their own Search Everywhere tab.

The index is a flat list of vectors held in memory and saved to `.semantic-code-search/index.bin`
inside your project. There's no database; for a single codebase, a brute-force search is fast enough.

## Limitations

- The chunker works from text, not a real parser. It doesn't account for braces inside strings or
  comments, single-line method bodies, or expression-bodied members. It's good enough for
  method-level results.
- Results land on the right method, not always the exact line.
- C# only for now.

## Development

```
./gradlew test
```

The logic tests (chunker, BM25, fusion, vector store) always run. The model-based tests load the real
ONNX models from `%USERPROFILE%\.semantic-code-search\models\` and skip themselves when the models
aren't present, so the build stays green without them.
