package pl.apargb.milkyway

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private val noteColors = listOf(
    Color(0xFFF0F5FD), // Blue
    Color(0xFFF1F7EE), // Green
    Color(0xFFFFF7E9), // Sand
    Color(0xFFFBEFF2), // Rose
    Color(0xFFF4F0FC), // Lavender
    Color(0xFFEDF7F5), // Mint
    Color(0xFFFFF1EB), // Peach
    Color(0xFFF0F1FA), // Indigo
)

// Use the immutable ID so edits, ordering and other devices retain the same tint.
internal fun noteCardColor(id: String, completed: Boolean = false): Color {
    val color = noteColors[Math.floorMod(id.hashCode(), noteColors.size)]
    return if (completed) lerp(Department.Processing.tint, color, .25f) else color
}
