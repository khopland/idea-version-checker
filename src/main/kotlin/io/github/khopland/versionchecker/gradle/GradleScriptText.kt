package io.github.khopland.versionchecker.gradle

/**
 * A `{ … }` block in a build script. [chain] is the call before the brace split at dots, for example
 * `pluginManagement`, `repositories` for `pluginManagement.repositories {`, and [args] is the text in its parentheses.
 * [open] and [close] are the offsets of the braces.
 */
internal class Block(val chain: List<String>, val args: String, val open: Int, var close: Int, val parent: Block?) {
    val children = mutableListOf<Block>()
    val name get() = chain.lastOrNull().orEmpty()

    /** Every name from the file down to this block. */
    val path: List<String> get() = parent?.path.orEmpty() + chain

    fun encloses(offset: Int) = offset > open && offset < close
}

/**
 * A Groovy or Kotlin build script read without a parser: [code] is the text with comments blanked, so offsets stay
 * the same, and [root] holds the blocks. Braces in strings and comments do not count.
 */
internal class ScriptText(val text: String) {
    val code: String
    val root = Block(emptyList(), "", -1, text.length, null)

    init {
        val code = StringBuilder(text)
        fun blank(from: Int, to: Int) {
            for (i in from until to) if (code[i] != '\n') code[i] = ' '
        }
        val n = text.length
        var current = root
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    blank(i, end)
                    i = end
                    continue
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    blank(i, end)
                    i = end
                    continue
                }
                text.startsWith("\"\"\"", i) || text.startsWith("'''", i) -> {
                    val end = text.indexOf(text.substring(i, i + 3), i + 3)
                    i = if (end < 0) n else end + 3
                    continue
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    i = j + 1
                    continue
                }
                c == '{' -> {
                    val (chain, args) = head(code, i)
                    val block = Block(chain, args, i, n, current)
                    current.children += block
                    current = block
                }
                c == '}' -> if (current !== root) {
                    current.close = i
                    current = current.parent!!
                }
            }
            i++
        }
        this.code = code.toString()
    }

    /** Every block, outermost first. */
    val blocks: List<Block> by lazy {
        val result = mutableListOf<Block>()
        fun add(block: Block): Unit = block.children.forEach { result += it; add(it) }
        add(root)
        result
    }

    /** The code inside [block], with the inside of its child blocks blanked. Offsets start after the opening brace. */
    fun own(block: Block): String {
        val start = block.open + 1
        val body = StringBuilder(code.substring(start, block.close))
        for (child in block.children) for (i in child.open + 1 - start until child.close - start) if (body[i] != '\n') body[i] = ' '
        return body.toString()
    }

    /** Whether [offset] is inside a block named [name] at any depth. */
    fun inside(offset: Int, name: String) = blocks.any { name in it.chain && it.encloses(offset) }

    private companion object {
        /** The call before the brace at [brace]: `maven("https://…") {` gives `maven` and `"https://…"`. */
        fun head(code: CharSequence, brace: Int): Pair<List<String>, String> {
            var j = brace - 1
            fun skipSpace() {
                while (j >= 0 && code[j].isWhitespace()) j--
            }
            skipSpace()
            var args = ""
            if (j >= 0 && code[j] == ')') {
                var depth = 0
                var k = j
                while (k >= 0) {
                    if (code[k] == ')') depth++
                    if (code[k] == '(' && --depth == 0) break
                    k--
                }
                if (k < 0) return emptyList<String>() to ""
                args = code.substring(k + 1, j)
                j = k - 1
                skipSpace()
            }
            val end = j + 1
            while (j >= 0 && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j--
            return code.substring(j + 1, end).split('.').filter { it.isNotEmpty() } to args
        }
    }
}
