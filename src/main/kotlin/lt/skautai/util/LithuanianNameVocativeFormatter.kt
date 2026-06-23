package lt.skautai.util

import java.util.Locale

object LithuanianNameVocativeFormatter {
    private val lithuanianLocale = Locale.forLanguageTag("lt-LT")

    private val exceptions = mapOf(
        "jurgis" to "jurgi",
        "kazys" to "kazį",
        "vytis" to "vyti"
    )

    fun firstNameVocative(fullName: String?): String {
        val firstName = fullName
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            .orEmpty()
        return vocative(firstName).ifBlank { "skaute" }
    }

    fun vocative(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""

        val lower = trimmed.lowercase(lithuanianLocale)
        val converted = exceptions[lower] ?: applySuffixRules(lower)
        return matchCase(trimmed, converted)
    }

    private fun applySuffixRules(lowerName: String): String = when {
        lowerName.endsWith("ias") -> lowerName.dropLast(3) + "iau"
        lowerName.endsWith("ius") -> lowerName.dropLast(3) + "iau"
        lowerName.endsWith("as") -> lowerName.dropLast(2) + "ai"
        lowerName.endsWith("us") -> lowerName.dropLast(2) + "au"
        lowerName.endsWith("is") -> lowerName.dropLast(2) + "i"
        lowerName.endsWith("ys") -> lowerName.dropLast(2) + "į"
        lowerName.endsWith("ė") -> lowerName.dropLast(1) + "e"
        lowerName.endsWith("a") -> lowerName
        else -> lowerName
    }

    private fun matchCase(original: String, convertedLower: String): String = when {
        original.all { !it.isLetter() || it.isUpperCase() } ->
            convertedLower.uppercase(lithuanianLocale)
        original.firstOrNull()?.isUpperCase() == true ->
            convertedLower.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(lithuanianLocale) else it.toString()
            }
        else -> convertedLower
    }
}
