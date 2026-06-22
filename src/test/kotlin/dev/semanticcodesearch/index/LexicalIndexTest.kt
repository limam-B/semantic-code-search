package dev.semanticcodesearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalIndexTest {

    private fun chunk(name: String, code: String) = CodeChunk("/$name.cs", 0, 5, name, code)

    @Test
    fun camelCaseQueryMatchesIdentifier() {
        val idx = LexicalIndex()
        idx.build(
            listOf(
                chunk("Hand.SetHandRotation", "public void SetHandRotation(Quaternion q) { _bone.rotation = q; }"),
                chunk("Player.Jump", "public void Jump() { velocity.y = 5f; }"),
                chunk("Foo.Bar", "public void Bar() { Console.WriteLine(); }"),
            )
        )

        val hits = idx.search("hand rotation", 3)
        assertTrue(hits.isNotEmpty())
        assertEquals("Hand.SetHandRotation", hits.first().chunk.symbolName)
    }

    @Test
    fun emptyIndexReturnsNothing() {
        assertTrue(LexicalIndex().search("anything", 5).isEmpty())
    }
}
