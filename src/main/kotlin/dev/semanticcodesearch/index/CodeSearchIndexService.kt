package dev.semanticcodesearch.index

import dev.semanticcodesearch.embedding.Embedder
import dev.semanticcodesearch.embedding.EmbedderModels
import dev.semanticcodesearch.embedding.ModelCatalog
import dev.semanticcodesearch.embedding.OnnxEmbedder
import dev.semanticcodesearch.embedding.OnnxReranker
import dev.semanticcodesearch.embedding.PoolingMode
import dev.semanticcodesearch.embedding.Reranker
import dev.semanticcodesearch.settings.CodeSearchSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections

/**
 * Owns the semantic index for one project: enumerate -> chunk -> embed (gte, GPU) -> store, plus the
 * two first-stage retrievers (dense + BM25), the cross-encoder rerank, persistence, auto-load on
 * startup, and incremental re-index when files change on disk.
 */
@Service(Service.Level.PROJECT)
class CodeSearchIndexService(private val project: Project) : Disposable {

    private val log = logger<CodeSearchIndexService>()
    private val chunkers: List<Chunker> = listOf(CSharpRegexChunker())
    private var store = VectorStore(dimension = 768)
    private val lexicalIndex = LexicalIndex()

    @Volatile private var embedder: Embedder? = null
    @Volatile private var reranker: Reranker? = null

    @Volatile var isReady: Boolean = false
        private set

    // Incremental re-index plumbing (debounced).
    private val reindexQueue = Collections.synchronizedSet(HashSet<VirtualFile>())
    private val pendingDeletes = Collections.synchronizedSet(HashSet<String>())
    private val reindexAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val indexLock = Any()   // guards store + lexicalIndex against search/reindex races

    init {
        // On-disk edits -> incrementally re-embed the changed .cs files.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (!isReady) return
                val changed = ArrayList<VirtualFile>()
                val deleted = ArrayList<String>()
                for (e in events) when (e) {
                    is VFileContentChangeEvent -> e.file.let { if (isCsInScope(it)) changed.add(it) }
                    is VFileCreateEvent -> e.file?.let { if (isCsInScope(it)) changed.add(it) }
                    is VFileDeleteEvent -> if (e.file.extension?.lowercase() == "cs") deleted.add(e.file.path)
                }
                if (changed.isNotEmpty() || deleted.isNotEmpty()) queueIncremental(changed, deleted)
            }
        })
    }

    // ---- config / paths ----

    private fun settings() = CodeSearchSettings.get(project).state

    private fun defaultModelsRoot(): Path =
        Paths.get(System.getProperty("user.home"), ".semantic-code-search", "models")

    private fun activeDimension(): Int = ModelCatalog.embedder(settings().embedderModel).dimension

    private fun modelDir(): Path {
        val d = settings().embedderModelDir
        return if (d.isNotBlank()) Paths.get(d) else defaultModelsRoot().resolve(settings().embedderModel)
    }

    private fun rerankerDir(): Path {
        val d = settings().rerankerModelDir
        return if (d.isNotBlank()) Paths.get(d) else defaultModelsRoot().resolve(settings().rerankerModel)
    }
    private fun indexFile(): Path = Paths.get(project.basePath ?: ".", ".semantic-code-search", "index.bin")

    /** User-configured CUDA / cuDNN bin dirs (Settings → GPU); null when blank (so the loader auto-detects). */
    private fun cudaDirOrNull(): Path? = settings().cudaDir.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
    private fun cudnnDirOrNull(): Path? = settings().cudnnDir.takeIf { it.isNotBlank() }?.let { Paths.get(it) }

    /** Pre-create the model folders so the user has a place to drop files into — leading-dot dirs
     *  like .semantic-code-search are awkward to create in Windows Explorer. */
    fun ensureModelDirsExist() {
        runCatching {
            Files.createDirectories(modelDir())
            Files.createDirectories(rerankerDir())
        }.onFailure { log.warn("could not create model dirs", it) }
    }

    private fun ensureStoreDim(dim: Int) {
        if (store.dimension != dim) store = VectorStore(dim)
    }

    /** Apply settings changes from the Settings panel. If the embedder config (dir / pooling /
     *  dimension) changed, the existing embeddings are invalid — wipe the index so the next Index
     *  rebuilds cleanly. The reranker is dropped too, so a new reranker folder takes effect. */
    fun onSettingsChanged() {
        if (builtEmbedderKey != null && builtEmbedderKey != embedderKey()) {
            synchronized(indexLock) {
                (embedder as? AutoCloseable)?.let { runCatching { it.close() } }
                embedder = null
                builtEmbedderKey = null
                store.clear()
                lexicalIndex.clear()
                runCatching { Files.deleteIfExists(indexFile()) }
                isReady = false
            }
            log.warn("Embedder settings changed — index wiped; run Index to rebuild.")
        }
        reranker?.let { runCatching { it.close() } }
        reranker = null
    }

    @Volatile
    private var builtEmbedderKey: String? = null

    private fun embedderKey(): String = "${modelDir()}|${settings().embedderModel}"

    @Synchronized
    private fun ensureEmbedder(): Embedder {
        embedder?.let { return it }
        val dir = modelDir()
        check(Files.exists(dir.resolve("tokenizer.json"))) { "Missing tokenizer.json in $dir — see README." }
        val info = ModelCatalog.embedder(settings().embedderModel)
        return OnnxEmbedder(
            EmbedderModels.resolveOnnxFile(dir),
            dir.resolve("tokenizer.json"),
            dimension = info.dimension,
            pooling = info.pooling,
            cudaDir = cudaDirOrNull(),
            cudnnDir = cudnnDirOrNull(),
        ).also { embedder = it; builtEmbedderKey = embedderKey() }
    }

    /** Lazy cross-encoder. Null (rerank skipped) if the model isn't present. */
    @Synchronized
    private fun ensureReranker(): Reranker? {
        reranker?.let { return it }
        val dir = rerankerDir()
        if (!Files.exists(dir.resolve("tokenizer.json"))) {
            log.warn("Reranker model not found at $dir — skipping rerank.")
            return null
        }
        return runCatching { OnnxReranker(EmbedderModels.resolveOnnxFile(dir), dir.resolve("tokenizer.json"), cudaDir = cudaDirOrNull(), cudnnDir = cudnnDirOrNull()) }
            .getOrElse { log.warn("Reranker init failed: ${it.message}"); null }
            .also { reranker = it }
    }

    // ---- build / load ----

    fun loadFromDisk(): Boolean = synchronized(indexLock) {
        ensureStoreDim(activeDimension())
        val ok = runCatching { store.load(indexFile()) }.getOrDefault(false)
        if (ok) lexicalIndex.build(store.allChunks())
        isReady = ok
        if (ok) log.warn("Semantic index loaded from disk: ${store.size} chunks")
        ok
    }

    fun rebuildIndex() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Building semantic code index", true) {
            override fun run(indicator: ProgressIndicator) {
                val emb = ensureEmbedder()
                val files = collectSourceFiles()
                synchronized(indexLock) {
                    ensureStoreDim(activeDimension())
                    store.clear()
                    val allChunks = ArrayList<CodeChunk>()
                    files.forEachIndexed { idx, vf ->
                        indicator.checkCanceled()
                        indicator.fraction = idx.toDouble() / files.size.coerceAtLeast(1)
                        indicator.text2 = vf.presentableUrl
                        runCatching { allChunks.addAll(indexOneFile(vf, emb)) }
                            .onFailure { log.warn("index failed for ${vf.path}", it) }
                    }
                    lexicalIndex.build(allChunks)
                    runCatching { store.save(indexFile()) }.onFailure { log.warn("index save failed", it) }
                    isReady = true
                    log.warn("Semantic index built: ${store.size} chunks from ${files.size} files (lexical ${lexicalIndex.size})")
                }
            }
        })
    }

    /** On-demand incremental index: embed only new/changed files, then show a result notification. */
    fun runIndex() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Indexing semantic code", true) {
            override fun run(indicator: ProgressIndicator) {
                val emb = ensureEmbedder()
                val files = collectSourceFiles()
                val keepPaths = files.mapTo(HashSet()) { it.path }
                var indexed = 0
                var skipped = 0
                var removed = 0
                synchronized(indexLock) {
                    ensureStoreDim(activeDimension())
                    val indexedPaths = store.allChunks().mapTo(HashSet()) { it.filePath }
                    // Drop chunks for files no longer in scope (newly excluded, or deleted).
                    for (gone in indexedPaths - keepPaths) { store.removeFile(gone); removed++ }
                    val lastTime = runCatching { Files.getLastModifiedTime(indexFile()).toMillis() }.getOrDefault(0L)
                    files.forEachIndexed { idx, vf ->
                        indicator.checkCanceled()
                        indicator.fraction = idx.toDouble() / files.size.coerceAtLeast(1)
                        indicator.text2 = vf.presentableUrl
                        val changed = vf.path !in indexedPaths || vf.timeStamp > lastTime
                        if (changed) {
                            runCatching { indexOneFile(vf, emb) }.onFailure { log.warn("index failed ${vf.path}", it) }
                            indexed++
                        } else {
                            skipped++
                        }
                    }
                    lexicalIndex.build(store.allChunks())
                    runCatching { store.save(indexFile()) }.onFailure { log.warn("index save failed", it) }
                    isReady = true
                }
                notifyInfo("Indexed $indexed, removed $removed out-of-scope, skipped $skipped — ${store.size} chunks · ${deviceOf(embedder) ?: "CPU"}.")
            }
        })
    }

    /** Wipe the index (memory + disk) so the next Index scans from scratch. */
    fun clean() {
        synchronized(indexLock) {
            store.clear()
            lexicalIndex.clear()
            runCatching { Files.deleteIfExists(indexFile()) }
            isReady = false
        }
        notifyInfo("Index cleaned — 0 chunks. Run Index to rebuild from scratch.")
    }

    private fun notifyInfo(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Semantic Code Search")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun queueIncremental(changed: List<VirtualFile>, deleted: List<String>) {
        reindexQueue.addAll(changed)
        pendingDeletes.addAll(deleted)
        reindexAlarm.cancelAllRequests()
        reindexAlarm.addRequest({ flushIncremental() }, 1500)
    }

    private fun flushIncremental() {
        val batch = synchronized(reindexQueue) { reindexQueue.toList().also { reindexQueue.clear() } }
        val dels = synchronized(pendingDeletes) { pendingDeletes.toList().also { pendingDeletes.clear() } }
        if (batch.isEmpty() && dels.isEmpty()) return
        val emb = runCatching { ensureEmbedder() }.getOrElse { log.warn("incremental: embedder unavailable", it); return }
        synchronized(indexLock) {
            for (p in dels) store.removeFile(p)
            for (vf in batch) runCatching { indexOneFile(vf, emb) }
                .onFailure { log.warn("incremental index failed ${vf.path}", it) }
            lexicalIndex.build(store.allChunks())
            runCatching { store.save(indexFile()) }.onFailure { log.warn("incremental save failed", it) }
        }
        log.warn("Incremental re-index: ${batch.size} changed, ${dels.size} removed (now ${store.size} chunks)")
    }

    private fun indexOneFile(vf: VirtualFile, emb: Embedder): List<CodeChunk> {
        val chunker = chunkers.firstOrNull { it.supports(vf) } ?: return emptyList()
        store.removeFile(vf.path)
        val text = String(vf.contentsToByteArray(), vf.charset)
        val chunks = chunker.chunk(vf, text)
        if (chunks.isEmpty()) return emptyList()
        val vecs = emb.embed(chunks.map { it.code })
        chunks.forEachIndexed { i, c -> store.add(c, vecs[i]) }
        return chunks
    }

    // ---- file selection ----

    private fun isCsInScope(vf: VirtualFile): Boolean {
        if (vf.isDirectory || vf.extension?.lowercase() != "cs") return false
        val p = vf.path
        if (!isUnderIncludedRoot(p)) return false
        if (DEFAULT_EXCLUDED_DIRS.any { p.contains("/$it/") }) return false
        if (isUserExcluded(p)) return false
        return isIndexableFile(vf)
    }

    /** True if the path falls under a user-configured excluded subpath (e.g. "Assets/Vendor"). */
    private fun isUserExcluded(path: String): Boolean {
        val excl = settings().excludedPaths
        if (excl.isEmpty()) return false
        val p = path.replace('\\', '/')
        return excl.any { e ->
            val norm = e.trim().trim('/').replace('\\', '/')
            norm.isNotEmpty() && (p.contains("/$norm/") || p.endsWith("/$norm"))
        }
    }

    /** Best-effort compute device of the loaded models (for the Settings panel / notifications). */
    fun deviceSummary(): String {
        val e = deviceOf(embedder) ?: return "Not loaded yet — run Index or a search first."
        return "Embedder: $e · Reranker: ${deviceOf(reranker) ?: "not loaded"}"
    }

    private fun deviceOf(x: Any?): String? = when (x) {
        is OnnxEmbedder -> if (x.usingGpu) "GPU (CUDA)" else "CPU"
        is OnnxReranker -> if (x.usingGpu) "GPU (CUDA)" else "CPU"
        else -> null
    }

    /** Folders to index: the user's project-relative "included" list resolved to VFS dirs, or the whole
     *  project when that list is empty. For a Unity project, add "Assets" (and "Packages"). */
    private fun includedRoots(): List<VirtualFile> {
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return emptyList()
        val roots = settings().includedPaths.mapNotNull { rel ->
            val norm = rel.trim().trim('/', '\\').replace('\\', '/')
            if (norm.isEmpty()) null else base.findFileByRelativePath(norm)
        }.filter { it.isDirectory }
        return roots.ifEmpty { listOf(base) }
    }

    /** True if [path] sits under one of the included roots (so an edited file is in our scope). */
    private fun isUnderIncludedRoot(path: String): Boolean {
        val p = path.replace('\\', '/')
        return includedRoots().any { root ->
            val r = root.path.replace('\\', '/')
            p == r || p.startsWith("$r/")
        }
    }

    private fun collectSourceFiles(): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        val pruneFilter = VirtualFileFilter { f ->
            !(f.isDirectory && (f.name in DEFAULT_EXCLUDED_DIRS || isUserExcluded(f.path)))
        }
        val collect = ContentIterator { vf ->
            if (!vf.isDirectory && vf.extension?.lowercase() == "cs" && isIndexableFile(vf)) out.add(vf)
            true
        }
        for (root in includedRoots()) VfsUtilCore.iterateChildrenRecursively(root, pruneFilter, collect)
        return out
    }

    private fun isIndexableFile(vf: VirtualFile): Boolean {
        val n = vf.name
        return !(n.endsWith(".g.cs", ignoreCase = true) || n.endsWith(".generated.cs", ignoreCase = true))
    }

    // ---- query: dense + lexical -> RRF -> rerank ----

    fun search(query: String): List<VectorStore.Hit> {
        if (query.isBlank() || store.size == 0) return emptyList()
        val st = settings()
        val emb = runCatching { ensureEmbedder() }.getOrElse { log.warn("embedder unavailable", it); return emptyList() }
        val qv = emb.embed(listOf(query)).firstOrNull() ?: return emptyList()
        val rr = if (st.enableReranker) ensureReranker() else null

        val fused = synchronized(indexLock) {
            val dense = store.topK(qv, st.firstStageK)
            val rankings = if (st.enableLexical) listOf(dense, lexicalIndex.search(query, st.firstStageK)) else listOf(dense)
            Rrf.fuse(rankings).take(st.firstStageK)
        }
        if (fused.isEmpty()) return emptyList()
        if (rr == null) return fused.take(st.topK)

        val scores = runCatching { rr.scores(query, fused.map { it.chunk.code }) }
            .getOrElse { log.warn("rerank failed, using fused order: ${it.message}"); return fused.take(st.topK) }
        return fused.indices.sortedByDescending { scores[it] }
            .map { VectorStore.Hit(fused[it].chunk, scores[it]) }
            .take(st.topK)
    }

    override fun dispose() {
        reindexAlarm.cancelAllRequests()
        (embedder as? AutoCloseable)?.let { runCatching { it.close() } }
        reranker?.let { runCatching { it.close() } }
    }

    companion object {
        fun getInstance(project: Project): CodeSearchIndexService =
            project.getService(CodeSearchIndexService::class.java)

        /** Always-skipped build / VCS / cache noise, regardless of the included folders. */
        private val DEFAULT_EXCLUDED_DIRS = setOf(
            ".git", ".idea", ".vs", "bin", "obj", "build", "out", "node_modules",
            "Library", "PackageCache", "Temp", "Logs",   // Unity build/cache noise
        )
    }
}
