package dev.aravind.assistant.policy

/** What the phone can tell about one text box on the screen. */
data class FieldInfo(
    val hint: String = "",
    val description: String = "",
    val viewId: String = "",
    /** Text from a label the app links to the box. */
    val labeledBy: String = "",
    /** The nearest label to the left of the box, or just above it. */
    val nearby: String = "",
    val multiLine: Boolean = false,
    val focused: Boolean = false,
    val height: Int = 0,
)

/**
 * Finds which text box you mean, like "To", "Subject" or the message in Gmail, in any app.
 * Apps label boxes differently, so it uses the box's hint, its id, and the label next to it, plus common other words.
 */
object FieldMatcher {
    private val groups: List<Set<String>> = listOf(
        setOf("to", "recipient", "recipients", "send to", "email address", "mail id", "email id", "receiver", "address to"),
        setOf("cc"),
        setOf("bcc"),
        setOf("subject", "title", "topic", "heading"),
        setOf(
            "body", "compose", "compose email", "message", "matter", "content", "text", "mail", "letter", "details",
            "description", "note", "notes", "comment", "caption", "post", "write", "reply",
        ),
        setOf("search", "search bar", "search box", "find"),
        setOf("name", "full name", "your name"),
        setOf("phone", "phone number", "mobile", "mobile number", "number", "contact number"),
    )

    private val bodyWords = groups[4]

    /** The other words for [wanted], like "matter" and "compose" for the message box. */
    fun synonyms(wanted: String): Set<String> {
        val w = wanted.trim().lowercase().removePrefix("the ").removeSuffix(" field").removeSuffix(" box").trim()
        return groups.firstOrNull { w in it } ?: setOf(w)
    }

    /** Speech often writes an email as "ravi at gmail dot com". This turns it into ravi@gmail.com. */
    fun spokenEmail(text: String): String {
        val t = text.trim().lowercase()
        if (t.contains('@')) return t.replace(" ", "")
        val m = Regex("^(.+?)\\s+at(?:\\s+the)?\\s+(.+)$").matchEntire(t) ?: return text
        val domain = m.groupValues[2].replace(Regex("\\s+dot\\s+|\\s*\\.\\s*"), ".").replace(" ", "")
        if (!Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(domain)) return text
        val user = m.groupValues[1].replace(Regex("\\s+dot\\s+"), ".").replace(Regex("\\s+underscore\\s+"), "_").replace(" ", "")
        return "$user@$domain"
    }

    fun isRecipient(wanted: String?): Boolean = wanted != null && "recipient" in synonyms(wanted)

    fun isBody(wanted: String?): Boolean = wanted != null && synonyms(wanted) === bodyWords

    private fun words(text: String): List<String> =
        text.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun hasPhrase(text: String, phrase: String): Boolean {
        val t = " " + words(text).joinToString(" ") + " "
        return t.contains(" $phrase ")
    }

    fun score(wanted: String, field: FieldInfo): Int {
        val names = synonyms(wanted)
        var score = 0
        val idTail = field.viewId.substringAfterLast('/')
        val idWords = words(idTail)
        if (names.any { it in idWords || (it.length >= 4 && idTail.lowercase().contains(it.replace(" ", ""))) }) score += 6
        for (text in listOf(field.hint, field.description, field.labeledBy)) {
            val clean = words(text).joinToString(" ")
            when {
                names.any { clean == it } -> score += 5
                names.any { hasPhrase(text, it) } -> score += 3
            }
        }
        val near = words(field.nearby).joinToString(" ")
        if (names.any { near == it }) score += 4 else if (names.any { hasPhrase(field.nearby, it) }) score += 1
        return score
    }

    /** The index of the box that best fits [wanted], or the focused or first box when nothing is named. */
    fun pick(wanted: String?, fields: List<FieldInfo>): Int? {
        if (fields.isEmpty()) return null
        if (wanted.isNullOrBlank()) return fields.indexOfFirst { it.focused }.takeIf { it >= 0 } ?: 0
        val scored = fields.mapIndexed { index, field -> index to score(wanted, field) }
        val best = scored.maxByOrNull { it.second }
        if (best != null && best.second > 0) return best.first
        // The message box often has no label, so the biggest multi-line box is used.
        if (isBody(wanted)) {
            return fields.indices.maxByOrNull { (if (fields[it].multiLine) 100_000 else 0) + fields[it].height }
        }
        return null
    }
}

/**
 * Turns "fill to as ravi@gmail.com, subject as leave letter and body as I am not well" into one
 * enter_text step per box, so filling a form needs no thinking.
 */
object FormFill {
    private const val FIELD =
        "to|recipient|cc|bcc|subject|title|body|message|compose(?: email)?|content|matter|mail|email(?: address)?|description|" +
            "notes?|comment|caption|(?:first |last |full )?name|phone(?: number)?|mobile(?: number)?|address|city|search(?: bar| box)?|" +
            "user ?name|amount"

    private const val PREFIX =
        "(?:please )?(?:fill|enter|type|write|put|add|set)(?: in| up| out)?(?: the| these| this| my| all)?" +
            "(?: details| data| form| fields?| matter| mail| email| gmail| below)?(?: in (?:that|this|it|there|the form|gmail|the mail))?" +
            "(?: like| as| with| like this)?[:,]?"

    private val marker = Regex(
        "(?i)(?:^|,\\s*|\\s+and\\s+|\\s+then\\s+|\\.\\s+)(?:(?:fill|set|write|type|enter|put|add|make)\\s+)?(?:the\\s+)?" +
            "($FIELD)(?:\\s+(?:field|box|section|line|area|bar))?\\s*(?:as|with|is|=|:|to be|should be)\\s+",
    )
    private val single = Regex(
        "(?i)^(?:please )?(?:fill|type|write|enter|put|add)(?: in)? (.+) (?:in|into|inside|on|as|for) (?:the )?($FIELD)(?: field| box| section| line| area| bar)?$",
    )

    /** The steps for a form-filling request, or null if it isn't one. */
    fun parse(text: String): List<ToolCall>? {
        val t = CommandRouter.normalize(text)
        single.matchEntire(t)?.let { m -> return listOfNotNull(call(m.groupValues[2], m.groupValues[1])).ifEmpty { null } }
        // "Fill the matter in that: to as Ravi, ..." starts with words that only introduce the boxes.
        return fields(t) ?: Regex("(?i)^$PREFIX\\s+").find(t)?.let { fields(t.substring(it.range.last + 1)) }
    }

    private fun fields(t: String): List<ToolCall>? {
        val matches = marker.findAll(t).toList()
        if (matches.isEmpty() || matches.first().range.first != 0) return null
        val calls = matches.mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: t.length
            call(match.groupValues[1], t.substring(match.range.last + 1, end).trim().trimEnd(',', '.'))
        }
        if (calls.any { it == null }) return null
        return calls.filterNotNull().ifEmpty { null }
    }

    private fun call(field: String, value: String): ToolCall? {
        if (value.isBlank()) return null
        return (ToolValidator.validate("enter_text", mapOf("field" to field.lowercase(), "text" to value)) as? Validated.Ok)?.call
    }
}
