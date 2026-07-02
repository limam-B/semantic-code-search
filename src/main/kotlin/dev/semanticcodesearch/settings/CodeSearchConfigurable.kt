package dev.semanticcodesearch.settings

import dev.semanticcodesearch.embedding.CudaNativeLoader
import dev.semanticcodesearch.embedding.ModelCatalog
import dev.semanticcodesearch.index.CodeSearchIndexService
import dev.semanticcodesearch.mcp.McpServerService
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultListModel
import javax.swing.JComponent

/** Settings | Tools | Semantic Code Search. */
class CodeSearchConfigurable(private val project: Project) : BoundConfigurable("Semantic Code Search") {

    private val state = CodeSearchSettings.get(project).state

    private val embedderCombo = ComboBox(ModelCatalog.embedders.map { it.key }.toTypedArray())
    private val rerankerCombo = ComboBox(ModelCatalog.rerankers.toTypedArray())
    private val embedderDirField = folderField()
    private val rerankerDirField = folderField()
    private val cudaDirField = folderField()
    private val cudnnDirField = folderField()
    private val dotnetDirField = folderField()

    private val includeModel = DefaultListModel<String>()
    private val includeList = JBList(includeModel).apply { visibleRowCount = 4 }
    private val excludeModel = DefaultListModel<String>()
    private val excludeList = JBList(excludeModel).apply { visibleRowCount = 4 }

    private fun folderField(): TextFieldWithBrowseButton {
        val f = TextFieldWithBrowseButton()
        f.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            FileChooser.chooseFile(descriptor, project, null)?.let { f.text = it.path }
        }
        return f
    }

    override fun createPanel(): DialogPanel {
        resetFromState()
        return panel {
            group("Retrieval") {
                row {
                    checkBox("Lexical (BM25) retrieval").bindSelected(state::enableLexical)
                        .comment("Fuse exact-identifier matches with the semantic results.")
                }
                row {
                    checkBox("Cross-encoder reranker").bindSelected(state::enableReranker)
                        .comment("Re-scores candidates for precision. Needs the reranker model present.")
                }
                row("Results shown:") { intTextField(1..200).bindIntText(state::topK) }
                row("First-stage candidates:") {
                    intTextField(1..500).bindIntText(state::firstStageK)
                        .comment("How many dense + lexical hits to fuse and rerank.")
                }
            }

            group("Compute") {
                row("Device:") { label(CodeSearchIndexService.getInstance(project).deviceSummary()) }
                row { comment("GPU (CUDA) once a model loads, CPU fallback. Reopen Settings after an Index to refresh.") }
            }

            group("GPU (CUDA)") {
                row("CUDA bin folder:") { cell(cudaDirField).align(AlignX.FILL) }
                row("cuDNN bin folder:") { cell(cudnnDirField).align(AlignX.FILL) }
                row {
                    button("Detect installed CUDA / cuDNN") {
                        CudaNativeLoader.detectCudaDir()?.let { cudaDirField.text = it.toString() }
                        CudaNativeLoader.detectCudnnDir()?.let { cudnnDirField.text = it.toString() }
                    }
                }
                row {
                    comment("For the GPU build only — the folders holding cudart64_12.dll (CUDA 12.x) and cudnn64_9.dll " +
                        "(cuDNN 9.x). Use Detect to auto-fill from a standard install, or set them by hand; blank = CPU. " +
                        "Restart Rider after changing. See the README.")
                }
            }

            group("C# chunking (Roslyn sidecar)") {
                row("dotnet folder:") { cell(dotnetDirField).align(AlignX.FILL) }
                row {
                    comment("C# files are parsed by a bundled Roslyn sidecar, which needs the .NET runtime. " +
                        "Leave blank to use 'dotnet' from PATH, or point to a .NET install folder. " +
                        "If .NET isn't found, indexing is blocked with a message — there is no fallback.")
                }
            }

            group("MCP server (for AI agents)") {
                row {
                    checkBox("Expose semantic search over HTTP (MCP)").bindSelected(state::mcpEnabled)
                        .comment("Lets AI agents (Claude Code, etc.) call a read-only search_code tool. Localhost only.")
                }
                row("Port:") {
                    intTextField(0..65535).bindIntText(state::mcpPort)
                        .comment("0 = auto-pick a free port on each project open. Set a fixed port to hard-code the URL.")
                }
                row("Status:") { label(mcpStatus()) }
                row {
                    comment("Add to your MCP client, e.g.:<br/>" +
                        "<code>{ \"type\": \"http\", \"url\": \"http://127.0.0.1:&lt;port&gt;/mcp\" }</code><br/>" +
                        "Apply, then reopen Settings to see the bound port.")
                }
            }

            group("Embedder") {
                row("Model:") { cell(embedderCombo) }
                row("Folder:") { cell(embedderDirField).align(AlignX.FILL) }
                row {
                    comment("The model sets pooling + dimension automatically. Folder = where its model.onnx + " +
                        "tokenizer.json live (blank = default location). Changing model or folder wipes the index — run Index.")
                }
            }

            group("Reranker") {
                row("Model:") { cell(rerankerCombo) }
                row("Folder:") { cell(rerankerDirField).align(AlignX.FILL) }
            }

            group("Indexing scope") {
                row {
                    comment("Included = folders to index, project-relative (e.g. Assets, Packages, src). " +
                        "Leave empty to index the whole project. On a Unity project, add Assets.")
                }
                row("Included folders:") {
                    cell(listPanel(includeList, "Folder to index, project-relative (e.g. Assets, src):", "Include Folder"))
                        .align(AlignX.FILL)
                }
                row {
                    comment("Excluded = subpaths or bare folder names to skip inside the included folders " +
                        "(e.g. Assets/Vendor, or just Tests). Build/VCS noise is always skipped. Run Index after changing scope.")
                }
                row("Excluded folders:") {
                    cell(listPanel(excludeList, "Path or folder name to exclude (e.g. Assets/Vendor, or just Tests):", "Exclude Path"))
                        .align(AlignX.FILL)
                }
            }

            group("Index") {
                row {
                    button("Index now") { CodeSearchIndexService.getInstance(project).runIndex() }
                    button("Clean") { CodeSearchIndexService.getInstance(project).clean() }
                }
                row {
                    comment("Index = scan new/changed files + drop excluded ones. Clean = wipe and start from 0. Results appear as a notification.")
                }
            }
        }
    }

    /** An add/remove editable string list backed by [list]'s model. */
    private fun listPanel(list: JBList<String>, prompt: String, title: String): JComponent {
        @Suppress("UNCHECKED_CAST")
        val model = list.model as DefaultListModel<String>
        return ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val entered = Messages.showInputDialog(project, prompt, title, null)?.trim()
                if (!entered.isNullOrEmpty() && !items(model).contains(entered)) model.addElement(entered)
            }
            .setRemoveAction { list.selectedValuesList.forEach { model.removeElement(it) } }
            .disableUpDownActions()
            .createPanel()
    }

    private fun mcpStatus(): String {
        val svc = McpServerService.getInstance(project)
        return if (svc.isRunning) "Running at http://127.0.0.1:${svc.boundPort}/mcp" else "Not running"
    }

    override fun apply() {
        super.apply()
        state.embedderModel = (embedderCombo.selectedItem as? String) ?: state.embedderModel
        state.embedderModelDir = embedderDirField.text.trim()
        state.rerankerModel = (rerankerCombo.selectedItem as? String) ?: state.rerankerModel
        state.rerankerModelDir = rerankerDirField.text.trim()
        state.includedPaths = items(includeModel).toMutableList()
        state.excludedPaths = items(excludeModel).toMutableList()
        state.cudaDir = cudaDirField.text.trim()
        state.cudnnDir = cudnnDirField.text.trim()
        state.dotnetPath = dotnetDirField.text.trim()
        CodeSearchIndexService.getInstance(project).onSettingsChanged()
        McpServerService.getInstance(project).restart()   // pick up mcpEnabled / mcpPort changes
    }

    override fun reset() {
        super.reset()
        resetFromState()
    }

    override fun isModified(): Boolean = super.isModified() ||
        (embedderCombo.selectedItem as? String) != state.embedderModel ||
        embedderDirField.text.trim() != state.embedderModelDir ||
        (rerankerCombo.selectedItem as? String) != state.rerankerModel ||
        rerankerDirField.text.trim() != state.rerankerModelDir ||
        items(includeModel) != state.includedPaths ||
        items(excludeModel) != state.excludedPaths ||
        cudaDirField.text.trim() != state.cudaDir ||
        cudnnDirField.text.trim() != state.cudnnDir ||
        dotnetDirField.text.trim() != state.dotnetPath

    private fun resetFromState() {
        embedderCombo.selectedItem = state.embedderModel
        embedderDirField.text = state.embedderModelDir
        rerankerCombo.selectedItem = state.rerankerModel
        rerankerDirField.text = state.rerankerModelDir
        // Auto-detect CUDA/cuDNN when unset and persist immediately — a GPU user gets it with no Apply.
        // (The Detect button re-runs detection to reset the fields if they were edited.)
        if (state.cudaDir.isBlank()) state.cudaDir = CudaNativeLoader.detectCudaDir()?.toString().orEmpty()
        if (state.cudnnDir.isBlank()) state.cudnnDir = CudaNativeLoader.detectCudnnDir()?.toString().orEmpty()
        cudaDirField.text = state.cudaDir
        cudnnDirField.text = state.cudnnDir
        dotnetDirField.text = state.dotnetPath
        includeModel.clear()
        state.includedPaths.forEach { includeModel.addElement(it) }
        excludeModel.clear()
        state.excludedPaths.forEach { excludeModel.addElement(it) }
    }

    private fun items(model: DefaultListModel<String>): List<String> =
        (0 until model.size).map { model.getElementAt(it) }
}
