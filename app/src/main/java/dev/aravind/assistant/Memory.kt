package dev.aravind.assistant

import android.content.Context
import dev.aravind.assistant.policy.SecretRedactor

/**
 * Local memory in the encrypted database: things you asked the assistant to remember, and, only if you turn
 * "Learn from my usage" on, which apps, contacts and music you use most. Passwords and codes are never stored.
 */
object MemoryStore {
    private val ask = Regex("(?i)\\b(remember|note that|note down|save that|keep in mind|don't forget|dont forget)\\b")

    /** Saving only happens when your own words asked for it. */
    fun userAskedToRemember(utterance: String) = ask.containsMatchIn(utterance)

    fun add(context: Context, fact: String): String {
        val clean = fact.trim().take(200)
        if (clean.isEmpty()) return "There was nothing to remember"
        if (SecretRedactor.containsSecret(clean)) return "That looks like a password or code, so I won't remember it"
        val dao = Db.get(context).memories()
        if (dao.all().any { it.text.equals(clean, ignoreCase = true) }) return "I already remember that"
        dao.insert(MemoryRow(text = clean, created = System.currentTimeMillis()))
        Audit.log(context, "memory_added")
        return "I'll remember: $clean"
    }

    fun list(context: Context): List<MemoryRow> = runCatching { Db.get(context).memories().all() }.getOrDefault(emptyList())

    fun update(context: Context, row: MemoryRow, text: String): Boolean {
        val clean = text.trim().take(200)
        if (clean.isEmpty() || SecretRedactor.containsSecret(clean)) return false
        Db.get(context).memories().update(row.copy(text = clean))
        return true
    }

    fun delete(context: Context, id: Long) = Db.get(context).memories().delete(id)

    /** Forgets every saved item that contains the words of [fact]. */
    fun forget(context: Context, fact: String): String {
        val words = fact.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val dao = Db.get(context).memories()
        val matches = dao.all().filter { row -> words.isNotEmpty() && words.all { row.text.lowercase().contains(it) } }
        matches.forEach { dao.delete(it.id) }
        if (matches.isNotEmpty()) Audit.log(context, "memory_forgot:${matches.size}")
        return if (matches.isEmpty()) "I didn't have anything saved about that" else "Forgot ${matches.size} saved item(s)"
    }

    fun recordUsage(context: Context, kind: String, name: String) {
        if (!Features.learnUsage(context)) return
        val clean = name.trim().take(60)
        if (clean.isEmpty() || SecretRedactor.containsSecret(clean)) return
        runCatching {
            val dao = Db.get(context).usage()
            val item = "$kind:${clean.lowercase()}"
            val old = dao.find(item)
            dao.put(UsageRow(item = item, kind = kind, name = clean, uses = (old?.uses ?: 0) + 1, lastUsed = System.currentTimeMillis()))
        }
    }

    fun usage(context: Context): List<UsageRow> = runCatching { Db.get(context).usage().all() }.getOrDefault(emptyList())

    fun deleteUsage(context: Context, item: String) = Db.get(context).usage().delete(item)

    fun clearAll(context: Context) {
        Db.get(context).memories().clear()
        Db.get(context).usage().clear()
        Audit.log(context, "memory_cleared")
    }

    /** What the AI is told about you at the start of each task. */
    fun promptText(context: Context): String {
        val facts = list(context).take(12).map { it.text }
        val top = runCatching {
            val dao = Db.get(context).usage()
            listOf("app" to "Apps used most", "contact" to "Contacts used most", "music" to "Music played most")
                .mapNotNull { (kind, label) -> dao.top(kind, 5).takeIf { it.isNotEmpty() }?.let { "$label: ${it.joinToString { row -> row.name }}" } }
        }.getOrDefault(emptyList())
        if (facts.isEmpty() && top.isEmpty()) return ""
        return buildString {
            if (facts.isNotEmpty()) append("Things the user asked you to remember:\n").append(facts.joinToString("\n") { "- $it" }).append('\n')
            top.forEach { append(it).append('\n') }
        }
    }
}
