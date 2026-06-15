package dev.hyphen.android

class BoundedLineBuffer(
    private val maxLines: Int,
    initialLine: String? = null,
) {
    private val lines = ArrayDeque<String>()

    init {
        require(maxLines > 0) { "maxLines must be positive" }
        if (initialLine != null) append(initialLine)
    }

    fun append(line: String) {
        if (lines.size == maxLines) lines.removeFirst()
        lines.addLast(line)
    }

    fun render(): String =
        lines.joinToString(separator = "\n", postfix = "\n")

    fun snapshot(): List<String> =
        lines.toList()
}
