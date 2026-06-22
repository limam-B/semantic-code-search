package dev.semanticcodesearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CSharpRegexChunkerTest {

    private val chunker = CSharpRegexChunker()

    @Test
    fun extractsMethodsWithTypeQualifiedNames() {
        val src = """
            namespace Game
            {
                public class Hand
                {
                    public void SetHandRotation(Quaternion q)
                    {
                        _bone.rotation = q;
                    }

                    public int Count()
                    {
                        return 1;
                    }
                }
            }
        """.trimIndent()

        val chunks = chunker.chunkText("/proj/Hand.cs", src)
        val names = chunks.map { it.symbolName }

        assertTrue("expected Hand.SetHandRotation in $names", names.contains("Hand.SetHandRotation"))
        assertTrue("expected Hand.Count in $names", names.contains("Hand.Count"))

        val m = chunks.first { it.symbolName == "Hand.SetHandRotation" }
        assertEquals("/proj/Hand.cs", m.filePath)
        assertTrue("end line should be past start", m.endLine > m.startLine)
        assertTrue("chunk code should contain the method", m.code.contains("SetHandRotation"))
    }

    @Test
    fun ignoresFilesWithNoMethods() {
        val chunks = chunker.chunkText("/proj/Empty.cs", "namespace X { }")
        assertTrue(chunks.isEmpty())
    }
}
