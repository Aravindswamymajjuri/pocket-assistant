package dev.aravind.assistant.policy

/**
 * Catches short commands that speech recognition misheard as similar-sounding words,
 * such as "the reed mescaline" for "read my screen". It only runs when nothing else matched,
 * only for short phrases, and only when one command is clearly the closest.
 */
object FuzzyCommand {
    private val commands = listOf(
        "read my screen", "read the screen", "explain my screen", "what is on my screen", "read my notifications",
        "lock my phone", "go back", "go home", "scroll down", "scroll up", "volume up", "volume down",
        "turn on the flashlight", "turn off the flashlight", "turn on wifi", "turn off wifi",
        "turn on bluetooth", "turn off bluetooth", "turn on mobile data", "turn off mobile data",
        "turn on hotspot", "turn off hotspot", "turn on airplane mode", "turn off airplane mode",
        "turn on location", "turn off location", "turn on dark mode", "turn off dark mode",
        "turn on silent mode", "vibration mode", "ring mode", "turn on do not disturb", "turn off do not disturb",
        "turn on power saving mode", "turn off power saving mode", "open reels", "play youtube shorts",
    )
    private val fillers = setOf("the", "a", "uh", "um", "hmm", "please", "hey", "ok", "okay", "so", "and")

    /** A sound code: similar-sounding letters share a digit, and vowels are dropped. */
    fun key(text: String): String {
        val groups = mapOf(
            'b' to '1', 'f' to '1', 'p' to '1', 'v' to '1',
            'c' to '2', 'g' to '2', 'j' to '2', 'k' to '2', 'q' to '2', 's' to '2', 'x' to '2', 'z' to '2',
            'd' to '3', 't' to '3', 'l' to '4', 'm' to '5', 'n' to '5', 'r' to '6',
        )
        val out = StringBuilder()
        var last = ' '
        for (c in text.lowercase()) {
            val code = groups[c] ?: if (c in "aeiouyhw") ' ' else continue
            if (code != ' ' && code != last) out.append(code)
            last = code
        }
        return out.toString()
    }

    private fun words(text: String) = text.lowercase().replace("wi-fi", "wifi").split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    /** Returns the command it most likely was, or null if it isn't clearly one of them. */
    fun match(heard: String): String? {
        val spoken = words(heard).dropWhile { it in fillers }
        if (spoken.isEmpty() || spoken.size > 6) return null
        val heardKey = key(spoken.joinToString(""))
        if (heardKey.length < 3) return null
        val scored = commands.map { it to ContactMatcher.distance(heardKey, key(words(it).joinToString(""))) }.sortedBy { it.second }
        val (best, distance) = scored.first()
        val runnerUp = scored.getOrNull(1)?.second ?: Int.MAX_VALUE
        val allowed = maxOf(1, heardKey.length / 5)
        val sizeClose = kotlin.math.abs(words(best).size - spoken.size) <= 2
        return best.takeIf { distance <= allowed && runnerUp > distance && sizeClose }
    }
}
