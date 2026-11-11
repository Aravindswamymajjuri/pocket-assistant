package dev.aravind.assistant.policy

class JsonRejected(message: String) : Exception(message)

/** Small strict JSON parser. It rejects duplicates, trailing text, deep nesting, and fractions. */
object StrictJson {
    private const val MAX_LENGTH = 4_000
    private const val MAX_DEPTH = 8

    fun parse(text: String): Any? {
        if (text.length > MAX_LENGTH) throw JsonRejected("too long")
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value(0)
        reader.skipSpace()
        if (!reader.atEnd()) throw JsonRejected("trailing text")
        return value
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw JsonRejected("unexpected end")

        private fun expect(c: Char) {
            if (peek() != c) throw JsonRejected("expected $c")
            i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonRejected("too deep")
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw JsonRejected("bad literal")
            i += w.length
            return v
        }

        private fun num(): Long {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toLongOrNull() ?: throw JsonRejected("bad number")
        }

        private fun str(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        val e = peek()
                        i++
                        when (e) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonRejected("bad escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonRejected("bad escape")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonRejected("bad escape")
                        }
                    }
                    c < ' ' -> throw JsonRejected("control character")
                    else -> out.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                out.add(value(depth + 1))
                skipSpace()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect(']')
                return out
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                val key = str()
                if (key in out) throw JsonRejected("duplicate key")
                skipSpace()
                expect(':')
                skipSpace()
                out[key] = value(depth + 1)
                skipSpace()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect('}')
                return out
            }
        }
    }
}

sealed interface ModelOutput {
    data class Reply(val text: String) : ModelOutput

    /** A short question back to the user when the request is unclear. */
    data class Ask(val question: String) : ModelOutput
    /** [final] means this step completes the request, so its result is told to you without thinking again. */
    data class Call(val call: ToolCall, val final: Boolean = false) : ModelOutput
    data class Plan(val calls: List<ToolCall>) : ModelOutput
    data class Invalid(val reason: String) : ModelOutput
}

/** The model must output a reply, one tool call, or a plan of at most five calls. Anything else is rejected. */
object ModelOutputParser {
    fun parse(raw: String): ModelOutput = parseWithThought(raw).second

    /**
     * Also returns the AI's optional "thought": one short sentence about what it's doing, shown on screen.
     * The thought is only displayed. It can't change which action runs, and codes in it are hidden.
     */
    fun parseWithThought(raw: String): Pair<String?, ModelOutput> {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = try {
            StrictJson.parse(text)
        } catch (e: JsonRejected) {
            return null to ModelOutput.Invalid(e.message ?: "invalid JSON")
        }
        @Suppress("UNCHECKED_CAST")
        val obj = root as? Map<String, Any?> ?: return null to ModelOutput.Invalid("not an object")
        val rawThought = obj["thought"]
        if (rawThought != null && rawThought !is String) return null to ModelOutput.Invalid("thought must be text")
        val thought = (rawThought as String?)
            ?.filter { !it.isISOControl() }?.let(SecretRedactor::redact)?.trim()?.take(MAX_THOUGHT)?.takeIf { it.isNotEmpty() }
        return thought to fromObject(obj - "thought")
    }

    private const val MAX_THOUGHT = 160

    private fun fromObject(obj: Map<String, Any?>): ModelOutput {
        if (obj.keys == setOf("ask")) {
            val question = (obj["ask"] as? String)?.trim()?.take(200) ?: return ModelOutput.Invalid("ask must be text")
            if (question.isEmpty()) return ModelOutput.Invalid("ask is empty")
            return ModelOutput.Ask(question)
        }
        if (obj.keys == setOf("reply")) {
            val reply = obj["reply"] as? String ?: return ModelOutput.Invalid("reply must be text")
            return ModelOutput.Reply(reply)
        }
        if (obj.keys == setOf("plan")) {
            val steps = obj["plan"] as? List<*> ?: return ModelOutput.Invalid("plan must be a list")
            if (steps.isEmpty() || steps.size > MAX_PLAN_STEPS) return ModelOutput.Invalid("plan has a bad length")
            val calls = ArrayList<ToolCall>()
            for (step in steps) {
                @Suppress("UNCHECKED_CAST")
                val item = step as? Map<String, Any?> ?: return ModelOutput.Invalid("plan step must be an object")
                when (val v = callFrom(item)) {
                    is Validated.Ok -> calls += v.call
                    is Validated.Rejected -> return ModelOutput.Invalid(v.reason)
                }
            }
            return ModelOutput.Plan(calls)
        }
        val final = obj["final"]
        if (final != null && final !is Boolean) return ModelOutput.Invalid("final must be true or false")
        return when (val v = callFrom(obj - "final")) {
            is Validated.Ok -> ModelOutput.Call(v.call, final == true)
            is Validated.Rejected -> ModelOutput.Invalid(v.reason)
        }
    }

    private const val MAX_PLAN_STEPS = 5

    private fun callFrom(obj: Map<String, Any?>): Validated {
        val extra = obj.keys - setOf("tool", "args")
        if (extra.isNotEmpty() || "tool" !in obj) return Validated.Rejected("unexpected keys")
        val args = when (val a = obj["args"]) {
            null -> emptyMap()
            is Map<*, *> -> a.entries.associate { it.key as String to it.value }
            else -> return Validated.Rejected("args must be an object")
        }
        return ToolValidator.validate(obj["tool"] as? String, args)
    }
}
