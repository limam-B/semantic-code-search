package dev.semanticcodesearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt

class VectorStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun norm(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val n = sqrt(s).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    @Test
    fun topKReturnsNearestByCosine() {
        val s = VectorStore(3)
        s.add(CodeChunk("/a.cs", 0, 1, "A", "codeA"), norm(floatArrayOf(1f, 0f, 0f)))
        s.add(CodeChunk("/b.cs", 0, 1, "B", "codeB"), norm(floatArrayOf(0f, 1f, 0f)))

        val hits = s.topK(norm(floatArrayOf(0.9f, 0.1f, 0f)), 2)
        assertEquals("A", hits.first().chunk.symbolName)
    }

    @Test
    fun saveLoadRoundTripPreservesCodeAndLines() {
        val s = VectorStore(3)
        s.add(CodeChunk("/a.cs", 2, 9, "A.M", "public void M() { }"), norm(floatArrayOf(1f, 0f, 0f)))
        val file = tmp.newFile("idx.bin").toPath()
        s.save(file)

        val loaded = VectorStore(3)
        assertTrue(loaded.load(file))
        assertEquals(1, loaded.size)
        val c = loaded.allChunks().first()
        assertEquals("A.M", c.symbolName)
        assertEquals("public void M() { }", c.code)   // code now persisted (needed for rerank)
        assertEquals(2, c.startLine)
        assertEquals(9, c.endLine)
    }

    @Test
    fun removeFileDropsOnlyThatFilesChunks() {
        val s = VectorStore(2)
        s.add(CodeChunk("/a.cs", 0, 1, "A", ""), norm(floatArrayOf(1f, 0f)))
        s.add(CodeChunk("/b.cs", 0, 1, "B", ""), norm(floatArrayOf(0f, 1f)))
        s.removeFile("/a.cs")
        assertEquals(1, s.size)
        assertEquals("B", s.allChunks().first().symbolName)
    }
}
