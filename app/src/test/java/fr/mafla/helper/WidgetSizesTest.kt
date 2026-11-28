package fr.mafla.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizesTest {

    @Test
    fun `minimum sizes from the sizing rule map to their cell count`() {
        // n cells = 70 × n − 30 dp
        assertEquals(1, WidgetSizes.cellsFor(40f))
        assertEquals(2, WidgetSizes.cellsFor(110f))
        assertEquals(3, WidgetSizes.cellsFor(180f))
        assertEquals(4, WidgetSizes.cellsFor(250f))
    }

    @Test
    fun `sizes between steps round to the nearest cell count`() {
        assertEquals(2, WidgetSizes.cellsFor(120f))
        assertEquals(3, WidgetSizes.cellsFor(160f))
    }

    @Test
    fun `tiny or missing sizes still take one cell`() {
        assertEquals(1, WidgetSizes.cellsFor(0f))
        assertEquals(1, WidgetSizes.cellsFor(5f))
    }
}
