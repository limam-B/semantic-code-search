package dev.semanticcodesearch.startup

import dev.semanticcodesearch.index.CodeSearchIndexService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: touch the index service (its init wires the incremental file listener) and load a
 * previously saved index from disk, so you don't have to Rebuild every session.
 */
class IndexStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.getService(CodeSearchIndexService::class.java)
        service.ensureModelDirsExist()
        service.loadFromDisk()
    }
}
