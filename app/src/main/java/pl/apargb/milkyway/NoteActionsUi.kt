package pl.apargb.milkyway

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val ReminderActiveColor = Color(0xFF926000)
internal val CompletedNoteColor = Color(0xFF287749)

@Composable
internal fun NoteActionsBar(important: Boolean, completed: Boolean, reminder: NoteReminder?, enabled: Boolean,
                            importantTag: String, completedTag: String,
                            onImportant: (Boolean) -> Unit, onCompleted: (Boolean) -> Unit, onReminder: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("note-actions-bar"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteAction("Ważne", important, enabled, ImportantNoteColor, Modifier.weight(1f).testTag(importantTag), onImportant)
            ReminderButton(reminder, enabled, modifier = Modifier.weight(1f), compact = true, onSelect = onReminder)
            NoteAction("Załatwione", completed, enabled, CompletedNoteColor, Modifier.weight(1f).testTag(completedTag), onCompleted)
        }
        ReminderStatusLabel(reminder)
    }
}

@Composable
private fun NoteAction(label: String, selected: Boolean, enabled: Boolean, color: Color,
                       modifier: Modifier, onChange: (Boolean) -> Unit) {
    Surface(modifier.heightIn(min = 44.dp).toggleable(value = selected, enabled = enabled, role = Role.Button, onValueChange = onChange),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) color.copy(alpha = .13f) else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}
