package dev.semanticcodesearch.search

import dev.semanticcodesearch.index.CodeSearchIndexService
import dev.semanticcodesearch.index.VectorStore
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.Processor
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Legacy Search Everywhere contributor that surfaces semantic hits and navigates to the exact
 * line. Pure presentation/navigation glue — all the real work (embed query, cosine top-k)
 * lives in [CodeSearchIndexService], so this class stays swappable for the new SeItemsProvider
 * API later.
 *
 * NOTE: SearchEverywhereContributor gained/changed members across platform versions. The set
 * below is the load-bearing core; if the 2026.1 (261) interface marks extra members abstract,
 * add them on first compile. Most have platform defaults.
 */
class SemanticSearchContributor(
    private val project: Project,
) : SearchEverywhereContributor<VectorStore.Hit> {

    override fun getSearchProviderId(): String = javaClass.name
    override fun getGroupName(): String = "Semantic Code"
    override fun getSortWeight(): Int = 500
    override fun showInFindResults(): Boolean = false

    // Give semantic search its OWN Search Everywhere tab instead of only living in the heavy "All"
    // tab. Combined with OpenSemanticSearchAction (opens straight onto this tab), Rider's other
    // contributors never initialize when you just want a code-meaning search.
    override fun isShownInSeparateTab(): Boolean = true

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in VectorStore.Hit>,
    ) {
        if (pattern.isBlank()) return
        val service = project.getService(CodeSearchIndexService::class.java) ?: return
        for (hit in service.search(pattern)) {
            progressIndicator.checkCanceled()
            if (!consumer.process(hit)) return
        }
    }

    override fun processSelectedItem(selected: VectorStore.Hit, modifiers: Int, searchText: String): Boolean {
        val vf = LocalFileSystem.getInstance().findFileByPath(selected.chunk.filePath) ?: return false
        OpenFileDescriptor(project, vf, selected.chunk.startLine, 0).navigate(true)
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in VectorStore.Hit> =
        object : ListCellRenderer<VectorStore.Hit> {
            private val delegate = DefaultListCellRenderer()
            override fun getListCellRendererComponent(
                list: JList<out VectorStore.Hit>,
                value: VectorStore.Hit,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val file = value.chunk.filePath.substringAfterLast('/')
                val label = "${value.chunk.symbolName}    —    $file:${value.chunk.startLine + 1}"
                return delegate.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }

    override fun getDataForItem(element: VectorStore.Hit, dataId: String): Any? = null

    override fun getElementPriority(element: VectorStore.Hit, searchPattern: String): Int =
        (element.score * 1000f).toInt()
}
