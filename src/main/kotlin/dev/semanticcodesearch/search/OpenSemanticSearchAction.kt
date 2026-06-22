package dev.semanticcodesearch.search

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Opens Search Everywhere directly on the Semantic Code tab, bypassing the "All" tab entirely so
 * none of Rider's other (heavy) contributors initialize. The tab id is the contributor's
 * search-provider id (its class name — see SemanticSearchContributor.getSearchProviderId).
 */
class OpenSemanticSearchAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SearchEverywhereManager.getInstance(project)
            .show(SemanticSearchContributor::class.java.name, "", e)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
