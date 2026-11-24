package homer.agentdebug.stubs

import homer.app.infra.FileOperations
import homer.app.infra.LocalFileOperations
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * The debugger's [FileOperations]: tolerant reads for a local run, over the production
 * [LocalFileOperations] for everything else — the streaming cap included.
 */
class DebugFileOperations : FileOperations by LocalFileOperations() {
    override fun fileSize(path: Path): Long = if (path.exists()) path.fileSize() else 0L

    override fun readText(path: Path): String = if (path.exists()) path.readText() else ""

    override fun listRegularFiles(
        dir: Path,
        limit: Int,
    ): List<Path> {
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        return dir
            .toFile()
            .walkTopDown()
            .filter { it.isFile }
            .map { it.toPath() }
            .take(limit)
            .toList()
    }

    override fun lastModifiedEpochSecond(path: Path): Long = path.getLastModifiedTime().toInstant().epochSecond
}
