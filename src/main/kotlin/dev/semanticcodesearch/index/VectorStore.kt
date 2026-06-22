package dev.semanticcodesearch.index

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue

/**
 * Flat in-memory store. All vectors are pre-L2-normalized, so cosine similarity is just a
 * dot product. Brute-force top-k: at <~200k chunks this is sub-/low-millisecond — no ANN
 * index and no database (pgvector etc.) needed for a single-user, single-codebase tool.
 */
class VectorStore(val dimension: Int) {

    private val vectors = ArrayList<FloatArray>()
    private val chunks = ArrayList<CodeChunk>()

    val size: Int get() = chunks.size

    /** Snapshot of all chunks (with code) — used to (re)build the lexical index. */
    fun allChunks(): List<CodeChunk> = ArrayList(chunks)

    fun clear() {
        vectors.clear()
        chunks.clear()
    }

    fun add(chunk: CodeChunk, normalizedVector: FloatArray) {
        require(normalizedVector.size == dimension) { "vector dim ${normalizedVector.size} != $dimension" }
        vectors.add(normalizedVector)
        chunks.add(chunk)
    }

    /** Drop every chunk from [path] (for incremental re-index of one file). */
    fun removeFile(path: String) {
        var w = 0
        for (r in chunks.indices) {
            if (chunks[r].filePath != path) {
                chunks[w] = chunks[r]
                vectors[w] = vectors[r]
                w++
            }
        }
        while (chunks.size > w) {
            chunks.removeAt(chunks.size - 1)
            vectors.removeAt(vectors.size - 1)
        }
    }

    data class Hit(val chunk: CodeChunk, val score: Float)

    /** [queryVector] must be L2-normalized. */
    fun topK(queryVector: FloatArray, k: Int): List<Hit> {
        require(queryVector.size == dimension)
        val heap = PriorityQueue<Hit>(k.coerceAtLeast(1), compareBy { it.score })
        for (i in vectors.indices) {
            val s = dot(queryVector, vectors[i])
            if (heap.size < k) heap.add(Hit(chunks[i], s))
            else if (s > heap.peek().score) {
                heap.poll()
                heap.add(Hit(chunks[i], s))
            }
        }
        return heap.sortedByDescending { it.score }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    // ---- persistence: one small binary file per project (vectors + metadata, no code bodies) ----

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        DataOutputStream(Files.newOutputStream(path).buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(dimension)
            out.writeInt(size)
            for (i in chunks.indices) {
                val c = chunks[i]
                out.writeUTF(c.filePath)
                out.writeInt(c.startLine)
                out.writeInt(c.endLine)
                out.writeUTF(c.symbolName)
                out.writeUTF(c.code)
                val v = vectors[i]
                for (d in 0 until dimension) out.writeFloat(v[d])
            }
        }
    }

    fun load(path: Path): Boolean {
        if (!Files.exists(path)) return false
        DataInputStream(Files.newInputStream(path).buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return false
            if (inp.readInt() != dimension) return false
            val n = inp.readInt()
            clear()
            repeat(n) {
                val fp = inp.readUTF()
                val s = inp.readInt()
                val e = inp.readInt()
                val name = inp.readUTF()
                val code = inp.readUTF()
                val v = FloatArray(dimension) { inp.readFloat() }
                chunks.add(CodeChunk(fp, s, e, name, code))
                vectors.add(v)
            }
        }
        return true
    }

    companion object {
        private const val MAGIC = 0x4A435357   // "JCSV" v2 — chunk code now persisted (for rerank)
    }
}
