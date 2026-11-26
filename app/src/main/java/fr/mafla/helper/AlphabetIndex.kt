package fr.mafla.helper

import java.text.Normalizer
import java.util.Locale

/**
 * Sections of an alphabetically sorted list, used by the app list gutter letters and the
 * index rail. Pure Kotlin so it can be unit tested.
 */
object AlphabetIndex {

    const val OTHER = "#"

    /** "Câmera" -> "C", "écran" -> "E", "1Password" -> "#", "" -> "". */
    fun sectionOf(label: String): String {
        val first = label.trimStart().firstOrNull() ?: return ""
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
            .firstOrNull() ?: return ""
        return if (base.isLetter()) base.toString().uppercase(Locale.getDefault()) else OTHER
    }

    /**
     * Letters in list order, each with the position of its first row. A letter that comes back
     * later in the list (a renamed app out of order) keeps its first position.
     */
    fun sections(labels: List<String>): List<Section> {
        val seen = LinkedHashMap<String, Int>()
        labels.forEachIndexed { position, label ->
            val letter = sectionOf(label)
            if (letter.isNotEmpty() && letter !in seen) seen[letter] = position
        }
        return seen.map { (letter, position) -> Section(letter, position) }
    }

    /** True when the row at [position] starts a new letter compared to the row before it. */
    fun startsSection(labels: List<String>, position: Int): Boolean {
        val letter = sectionOf(labels.getOrNull(position) ?: return false)
        if (letter.isEmpty()) return false
        if (position == 0) return true
        return sectionOf(labels[position - 1]) != letter
    }

    data class Section(val letter: String, val position: Int)
}
