package pl.apargb.milkyway

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun currentNoteAuthor(): String? {
    val session by CloudSession.get(LocalContext.current).state.collectAsState()
    return session.noteAuthor
}

@Composable
internal fun NoteAuthorLabel(author: String?, modifier: Modifier = Modifier) {
    author?.let { Text("Autor: $it", modifier, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
