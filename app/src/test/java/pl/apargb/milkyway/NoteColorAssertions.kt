package pl.apargb.milkyway

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun SemanticsNodeInteraction.assertTextColor(expected: Color): SemanticsNodeInteraction {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
    assertTrue("Text must expose its actual rendered layout", layouts.isNotEmpty())
    layouts.forEach { assertEquals(expected, it.layoutInput.style.color) }
    return this
}
