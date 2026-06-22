# Semantic Code Search

Local, fully-offline semantic code search for **JetBrains Rider**. Ask a natural-language question —
*"where is the hand rotation set?"* — and jump to the exact `file:line`, surfaced inside double-Shift
**Search Everywhere**. Embeddings run in-process via ONNX. No server, no cloud.

> Built and tested against **Rider 2026.1.2 (build 261)**. C#-focused — the chunker understands C#.

## Install

Grab a zip from the [Releases](https://github.com/limam-B/semantic-code-search/releases) (or build it
below), then **Settings → Plugins → ⚙ → Install Plugin from Disk** and restart Rider. Each release ships
two flavors:

- **`…-cpu.zip`** — smaller (~110 MB), runs on Windows / Linux / macOS. Pick this unless you need GPU.
- **`…-gpu.zip`** — large (~0.5 GB), Windows / Linux; uses CUDA when CUDA 12.x + cuDNN 9.x are present,
  and auto-falls back to CPU otherwise.

## Architecture (everything behind a seam)

```
Chunker (frontend-only, text-based)   CSharpRegexChunker  (brace-match, Allman-aware)
  -> Embedder (in-process ONNX, CUDA/GPU)  OnnxEmbedder    (gte-modernbert-base, CLS pooling, 768-d)
  -> Retrieval — two-stage hybrid:
       dense   VectorStore   brute-force cosine top-30 (normalized; no DB / no pgvector)
       lexical LexicalIndex  BM25 top-30 (camelCase-aware: "hand rotation" hits SetHandRotation)
       fuse    Rrf           Reciprocal Rank Fusion -> ~30 merged candidates
       rerank  OnnxReranker  cross-encoder (gte-reranker) re-sorts -> right line (skipped if model absent)
  -> Search Everywhere surface             legacy SearchEverywhereContributor (own tab, auto-bridged)
```

Each layer is an interface (`Chunker`, `Embedder`, `Reranker`) so the model, the chunking strategy,
and the Search-Everywhere surface can each be swapped without touching the others.

### Why these choices

- **No PSI / text-based chunker.** In Rider the C# AST lives in the ReSharper *backend*, unreachable
  from a pure-frontend plugin. The chunker recovers type + method boundaries from text by
  brace-matching (tuned for Allman style). A tree-sitter or backend-PSI chunker can drop in later.
- **Legacy `SearchEverywhereContributor`, not the new `SeItemsProvider`/`SeTabFactory`.** On 2026.1
  the new API is `@ApiStatus.Experimental`, with no docs/samples, and a custom `SeTab` is ~12 abstract
  suspend methods over an undocumented type ecosystem. Legacy is documented, simpler, and auto-bridges
  into the new architecture. The isolated core makes the eventual port cheap.
- **No pgvector / no DB.** A single codebase is well under ~200k chunks; brute-force cosine over a flat
  normalized `float[]` is sub-millisecond and needs nothing else.
- **In-process ONNX (not Ollama).** Self-contained and offline at runtime; no model server to manage.

## One-time setup: get the models (offline thereafter)

The plugin auto-creates the model folders on first launch — just open them and drop the files in.
Location: `%USERPROFILE%\.semantic-code-search\models\` (i.e. `C:\Users\<you>\.semantic-code-search\models\`).
Prefer a normal path? Set a custom **Folder** per model in **Settings → Tools → Semantic Code Search**
(handy since the leading-dot folder is awkward to create by hand in Explorer):

```
.semantic-code-search\models\
    gte-modernbert-base\            model.onnx + tokenizer.json   (HF Alibaba-NLP/gte-modernbert-base)
    gte-reranker-modernbert-base\   model.onnx + tokenizer.json   (HF Alibaba-NLP/gte-reranker-modernbert-base)
```

The reranker is optional — without it, search runs dense + BM25 + RRF only (toggle it in Settings).

## Indexing scope (included / excluded folders)

By default the plugin indexes **the whole project** (build/VCS/cache noise like `bin`, `obj`, `.git`,
`node_modules`, and Unity's `Library`/`PackageCache`/`Temp`/`Logs` is always skipped). Narrow or widen
it in **Settings → Tools → Semantic Code Search → Indexing scope**:

- **Included folders** — project-relative folders to index (e.g. `src`). Leave empty for the whole
  project. **On a Unity project, add `Assets`** (and `Packages` if you want package code indexed).
- **Excluded folders** — subpaths or bare folder names to skip *within* the included folders
  (e.g. `Assets/Vendor`, or just `Tests`).

Only `.cs` files are indexed; generated `*.g.cs` / `*.generated.cs` are skipped. Run **Index** after
changing scope.

## Build from source

**Requirement: JDK 21.** Gradle 8.13 can't run on JDK 24+; IntelliJ IDEA's bundled JBR 21 works.
Easiest: open the project in **IntelliJ IDEA** and run the Gradle **`buildPlugin`** task. Or from a
terminal with `JAVA_HOME` pointing at a JDK 21:

```
./gradlew buildPlugin
```

The installable plugin lands in `build/distributions/semantic-code-search-<version>.zip`. Install it in
Rider via **Settings → Plugins → ⚙ → Install Plugin from Disk**, then restart Rider. This is the GPU
flavor by default; add **`-Pgpu=false`** for the slim CPU-only zip.

Notes:
- The build auto-detects a local Rider install for `runIde` (the dev sandbox). If none is found it
  downloads the 2026.1.2 SDK — `buildPlugin` still works. Point at your own Rider with
  `-PriderLocalPath=<path>` or `riderLocalPath=<path>` in `gradle.properties` (only needed for `runIde`).
- Drop the models into `%USERPROFILE%\.semantic-code-search\models\` (see "One-time setup" above) —
  they are NOT bundled in the source/plugin.
- GPU/CUDA is optional; without it everything runs on CPU.

Use it: **Rebuild Semantic Code Index** (action, or the Settings "Index now" button), then double-Shift →
ask a natural-language question → the **Semantic Code** tab lists hits; Enter jumps to the line.

## Status / known rough edges (honest)

- **Chunker is heuristic.** Brace-matching doesn't skip braces inside strings/comments and misses
  same-line-brace and expression-bodied members. Fine for method-level recall.
- **Lands on the right method, not always the exact line.** Exact-line precision leans on the
  lexical RRF + cross-encoder rerank stage.
- **First run** fetches the DJL tokenizer native once; the ONNX runtime native ships in-jar. Offline
  after that.

## Tests

```
./gradlew test
```

Pure-logic tests (chunker, BM25, RRF, vector store) always run. The real-model integration tests load
the actual ONNX models from `%USERPROFILE%\.semantic-code-search\models\` and self-skip when the models
aren't present, so a clean build stays green.
