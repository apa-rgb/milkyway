package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.security.MessageDigest

internal fun warehousePinMatches(pin: String): Boolean {
    if (pin.length != 4 || pin.any { it !in '0'..'9' }) return false
    val expected = "34ff3bb3732ca5436824c18fc789ff0507ae31807ca3f782b7d7740235d5d719"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8)))
}

@Composable
internal fun WarehouseDeleteDialog(title: String, summary: String, saving: Boolean, error: String?,
                                   confirmTag: String, onDelete: (String) -> Unit, onClose: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var incorrect by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { pin = ""; incorrect = false } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = { if (!saving) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(summary)
            OutlinedTextField(pin, { pin = it.filter { digit -> digit in '0'..'9' }.take(4); incorrect = false },
                label = { Text("PIN do usuwania") }, singleLine = true, enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(), isError = incorrect,
                modifier = Modifier.fillMaxWidth().testTag("warehouse-delete-pin"))
            if (incorrect) Text("Nieprawidłowy PIN.", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, enabled = !saving) { Text("Anuluj") }
            TextButton(onClick = {
            val submitted = pin; pin = ""
            if (warehousePinMatches(submitted)) onDelete(submitted) else incorrect = true
        }, enabled = !saving && pin.length == 4, modifier = Modifier.testTag(confirmTag)) { Text("Usuń") }
            }
        }
        }
    }
}
