package dev.semanticcodesearch.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/** Persisted, project-scoped settings for the semantic search, editable from the Settings panel. */
@Service(Service.Level.PROJECT)
@State(name = "CodeSearchSettings", storages = [Storage("semanticCodeSearch.xml")])
class CodeSearchSettings : PersistentStateComponent<CodeSearchSettings.State> {

    data class State(
        var enableLexical: Boolean = true,
        var enableReranker: Boolean = true,
        var topK: Int = 30,
        var firstStageK: Int = 30,
        var embedderModel: String = "gte-modernbert-base",          // catalog key (sets pooling + dimension)
        var embedderModelDir: String = "",                          // folder; blank = default location
        var rerankerModel: String = "gte-reranker-modernbert-base",
        var rerankerModelDir: String = "",                          // folder; blank = default location
        var includedPaths: MutableList<String> = mutableListOf(),   // project-relative folders to index; empty = whole project
        var excludedPaths: MutableList<String> = mutableListOf(),   // subpaths/names to skip within the included folders
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    companion object {
        fun get(project: Project): CodeSearchSettings = project.getService(CodeSearchSettings::class.java)
    }
}
