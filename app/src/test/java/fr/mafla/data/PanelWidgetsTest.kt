package fr.mafla.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelWidgetsTest {

    @Test
    fun `round trips widgets in order`() {
        val widgets = listOf(PanelWidget(12, 176), PanelWidget(3, 88), PanelWidget(40, 0))
        assertEquals(widgets, PanelWidgets.decode(PanelWidgets.encode(widgets)))
    }

    @Test
    fun `nothing stored reads as no widgets`() {
        assertTrue(PanelWidgets.decode(null).isEmpty())
        assertTrue(PanelWidgets.decode("").isEmpty())
    }

    @Test
    fun `broken json reads as no widgets`() {
        assertTrue(PanelWidgets.decode("{not json").isEmpty())
    }

    @Test
    fun `drops invalid ids and repeats, keeps the first`() {
        val json = """[{"id":0,"height":88},{"id":-4},{"id":7,"height":100},{"id":7,"height":300},"x"]"""
        assertEquals(listOf(PanelWidget(7, 100)), PanelWidgets.decode(json))
    }

    @Test
    fun `a missing or negative height means the widget's default`() {
        val json = """[{"id":5},{"id":6,"height":-20}]"""
        assertEquals(listOf(PanelWidget(5, 0), PanelWidget(6, 0)), PanelWidgets.decode(json))
    }
}
