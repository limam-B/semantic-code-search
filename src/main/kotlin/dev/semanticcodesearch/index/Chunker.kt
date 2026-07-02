package dev.semanticcodesearch.index

import com.intellij.openapi.vfs.VirtualFile

/**
 * Splits a source file into [CodeChunk]s.
 *
 * Why a seam: Rider's C# AST lives in the ReSharper *backend*, unreachable from a pure-Kotlin
 * frontend plugin. Rather than reach into the backend, the active impl ([RoslynChunker]) runs our
 * own Roslyn parse in an out-of-process `dotnet` sidecar and streams chunk boundaries back. Keeping
 * this an interface means that sidecar — or any future chunker — drops in without touching the rest
 * of the pipeline.
 */
interface Chunker {
    /** True if this chunker handles the given file (by extension, etc.). */
    fun supports(file: VirtualFile): Boolean

    /** [text] is the file content; returns chunks with 0-based, inclusive line ranges. */
    fun chunk(file: VirtualFile, text: CharSequence): List<CodeChunk>
}
