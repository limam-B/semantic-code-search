package dev.semanticcodesearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrfTest {

    private fun hit(name: String, score: Float) =
        VectorStore.Hit(CodeChunk("/$name.cs", 0, 1, name, ""), score)

    @Test
    fun itemsHighInBothListsRankAboveTailAndAreDeduped() {
        val dense = listOf(hit("X", 0.9f), hit("Y", 0.8f), hit("Z", 0.1f))
        val lexical = listOf(hit("Y", 5f), hit("X", 4f), hit("W", 1f))

        val fused = Rrf.fuse(listOf(dense, lexical))
        val names = fused.map { it.chunk.symbolName }

        // no duplicates (merged by identity)
        assertEquals(names.size, names.toSet().size)
        // X and Y appear in both lists -> ahead of Z and W (single-list tail)
        assertTrue(names.indexOf("X") < names.indexOf("Z"))
        assertTrue(names.indexOf("Y") < names.indexOf("W"))
    }
}
