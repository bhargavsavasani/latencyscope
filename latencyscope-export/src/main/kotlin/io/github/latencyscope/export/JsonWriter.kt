package io.github.latencyscope.export

/**
 * Minimal JSON writer.
 *
 * Hand-rolled on purpose: core carries no serialization dependency, and
 * forcing kotlinx-serialization and its compiler plugin on every consumer in
 * order to emit one flat document would be a poor trade.
 */
internal class JsonWriter(private val out: StringBuilder = StringBuilder()) {

    private var needsComma = false

    fun obj(block: JsonWriter.() -> Unit): JsonWriter = apply {
        open('{')
        block()
        close('}')
    }

    fun arr(block: JsonWriter.() -> Unit): JsonWriter = apply {
        open('[')
        block()
        close(']')
    }

    fun name(key: String): JsonWriter = apply {
        separate()
        writeString(key)
        out.append(':')
        needsComma = false
    }

    fun value(value: String?): JsonWriter = apply {
        separate()
        if (value == null) out.append("null") else writeString(value)
        needsComma = true
    }

    fun value(value: Long?): JsonWriter = apply {
        separate()
        out.append(value?.toString() ?: "null")
        needsComma = true
    }

    fun value(value: Int?): JsonWriter = apply {
        separate()
        out.append(value?.toString() ?: "null")
        needsComma = true
    }

    fun value(value: Boolean): JsonWriter = apply {
        separate()
        out.append(if (value) "true" else "false")
        needsComma = true
    }

    /** Rounded to two decimals; a latency report has no use for 17 digits. */
    fun value(value: Double?): JsonWriter = apply {
        separate()
        if (value == null || value.isNaN() || value.isInfinite()) {
            out.append("null")
        } else {
            out.append(round(value))
        }
        needsComma = true
    }

    fun field(key: String, value: String?): JsonWriter = name(key).value(value)
    fun field(key: String, value: Long?): JsonWriter = name(key).value(value)
    fun field(key: String, value: Int?): JsonWriter = name(key).value(value)
    fun field(key: String, value: Double?): JsonWriter = name(key).value(value)
    fun field(key: String, value: Boolean): JsonWriter = name(key).value(value)

    fun objectField(key: String, block: JsonWriter.() -> Unit): JsonWriter = name(key).obj(block)
    fun arrayField(key: String, block: JsonWriter.() -> Unit): JsonWriter = name(key).arr(block)

    override fun toString(): String = out.toString()

    private fun open(char: Char) {
        separate()
        out.append(char)
        needsComma = false
    }

    private fun close(char: Char) {
        out.append(char)
        needsComma = true
    }

    private fun separate() {
        if (needsComma) out.append(',')
    }

    /**
     * Escapes the JSON-mandated characters, plus `<`, `>` and `&`.
     *
     * Those three are not required by JSON. They are escaped because this
     * document is embedded in a `script` element in the HTML report: a URL
     * containing a closing script tag would otherwise end the element early
     * and let recorded data rewrite the page.
     */
    private fun writeString(value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '<' -> out.append("\\u003c")
                '>' -> out.append("\\u003e")
                '&' -> out.append("\\u0026")
                // Legal in JSON, fatal in JavaScript source.
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                else -> if (char < ' ') {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    private fun round(value: Double): String {
        val scaled = Math.round(value * ROUNDING) / ROUNDING
        return if (scaled == Math.floor(scaled)) scaled.toLong().toString() else scaled.toString()
    }

    private companion object {
        const val ROUNDING = 100.0
    }
}
