package fr.mafla.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetIndexTest {

    @Test
    fun `accented letters fold to their base letter`() {
        assertEquals("C", AlphabetIndex.sectionOf("Câmera"))
        assertEquals("E", AlphabetIndex.sectionOf("écran"))
        assertEquals("A", AlphabetIndex.sectionOf("  Ágata"))
    }

    @Test
    fun `digits and symbols go to the other section`() {
        assertEquals(AlphabetIndex.OTHER, AlphabetIndex.sectionOf("1Password"))
        assertEquals(AlphabetIndex.OTHER, AlphabetIndex.sectionOf("@home"))
    }

    @Test
    fun `blank labels have no section`() {
        assertEquals("", AlphabetIndex.sectionOf(""))
        assertEquals("", AlphabetIndex.sectionOf("   "))
    }

    @Test
    fun `sections keep list order and first position`() {
        val labels = listOf("Agenda", "Amazon", "Banco", "Calculadora", "Câmera", "Chrome", "")
        val sections = AlphabetIndex.sections(labels)
        assertEquals(listOf("A", "B", "C"), sections.map { it.letter })
        assertEquals(listOf(0, 2, 3), sections.map { it.position })
    }

    @Test
    fun `a letter seen again later keeps its first position`() {
        val labels = listOf("Agenda", "Banco", "Alarme")
        val sections = AlphabetIndex.sections(labels)
        assertEquals(listOf("A", "B"), sections.map { it.letter })
        assertEquals(0, sections.first().position)
    }

    @Test
    fun `only the first row of a letter starts a section`() {
        val labels = listOf("Calculadora", "Câmera", "Drive")
        assertTrue(AlphabetIndex.startsSection(labels, 0))
        assertFalse(AlphabetIndex.startsSection(labels, 1))
        assertTrue(AlphabetIndex.startsSection(labels, 2))
        assertFalse(AlphabetIndex.startsSection(labels, 3))
    }
}
