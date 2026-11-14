package homer.app

/** One row of a doc field table: the field name(s) of its first cell, its required flag and its default cell. */
internal data class FieldRow(
    val names: List<String>,
    val required: String?,
    val default: String?,
)

/** A Markdown table whose first header cell is `Field`, under the nearest heading above it. */
internal data class FieldTable(
    val heading: String,
    val line: Int,
    val rows: List<FieldRow>,
)

/**
 * Reads the field tables of a Markdown doc (`app/SERVER.md`, `app/AGENT.md`). Tables inside fenced code
 * blocks are ignored. A missing `Required` column reads as not required, a missing `Default` column as
 * no default to compare.
 */
internal object DocFieldTables {
    private val heading = Regex("^#+ +(.+)$")
    private val separator = Regex("^\\|[-| :]+\\|$")
    private val cellSplit = Regex("(?<!\\\\)\\|")
    private val codeSpan = Regex("`([^`]+)`")

    fun parse(markdown: String): List<FieldTable> {
        val lines = markdown.lines()
        val tables = mutableListOf<FieldTable>()
        var currentHeading = ""
        var inFence = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("```")) inFence = !inFence
            if (!inFence) heading.find(line)?.let { currentHeading = it.groupValues[1].trim() }
            if (!inFence && isTableStart(lines, i)) {
                val end = (i + 2 until lines.size).firstOrNull { !lines[it].startsWith("|") } ?: lines.size
                table(currentHeading, i, lines.subList(i, end))?.let(tables::add)
                i = end
            } else {
                i++
            }
        }
        return tables
    }

    private fun isTableStart(
        lines: List<String>,
        i: Int,
    ): Boolean = lines[i].startsWith("|") && i + 1 < lines.size && separator.matches(lines[i + 1].trim())

    private fun table(
        heading: String,
        index: Int,
        lines: List<String>,
    ): FieldTable? {
        val header = cells(lines[0])
        if (header.first() != "Field") return null
        val rows =
            lines.drop(2).map { line ->
                val row = header.zip(cells(line)).toMap()
                FieldRow(names(row.getValue("Field")), row["Required"], row["Default"])
            }
        return FieldTable(heading, index + 1, rows)
    }

    private fun cells(line: String): List<String> =
        line
            .trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split(cellSplit)
            .map { it.trim() }

    /** `` `a` `` → [a]; `` `min` / `max` `` → [min, max]; a cell without code spans is its own name. */
    private fun names(cell: String): List<String> =
        codeSpan
            .findAll(cell)
            .map { it.groupValues[1] }
            .toList()
            .ifEmpty { listOf(cell) }
}
