package dev.semanticcodesearch.search

import dev.semanticcodesearch.index.CodeSearchIndexService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Manual "rebuild the whole semantic index" trigger (also reachable from Search Everywhere). */
class RebuildIndexAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        project.getService(CodeSearchIndexService::class.java).rebuildIndex()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}
