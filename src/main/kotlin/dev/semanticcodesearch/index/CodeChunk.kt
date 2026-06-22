package dev.semanticcodesearch.index

/** One embeddable unit of code plus the metadata needed to navigate back to it. */
data class CodeChunk(
    val filePath: String,   // VirtualFile.path (absolute, '/'-separated)
    val startLine: Int,     // 0-based, inclusive
    val endLine: Int,       // 0-based, inclusive
    val symbolName: String, // e.g. "RagdollHandUtilities.SetPrepareRotation"
    val code: String,       // the text fed to the embedder (not persisted in the index)
)
