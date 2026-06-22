package dev.semanticcodesearch.search

import dev.semanticcodesearch.index.VectorStore
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Registered via the `searchEverywhereContributor` EP in plugin.xml. */
class SemanticSearchContributorFactory : SearchEverywhereContributorFactory<VectorStore.Hit> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<VectorStore.Hit> {
        val project = requireNotNull(initEvent.getData(CommonDataKeys.PROJECT)) {
            "Semantic code search requires an open project"
        }
        return SemanticSearchContributor(project)
    }
}
