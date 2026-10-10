package pl.apargb.milkyway

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun ComposeContentTestRule.openShift(shift: Int) {
    onNodeWithTag("home-shift-$shift").performClick()
    onNodeWithTag("shift-pin").performTextInput("5522")
    onNodeWithTag("shift-unlock").performClick()
    onNodeWithTag("menu-Notes").assertIsDisplayed()
}
