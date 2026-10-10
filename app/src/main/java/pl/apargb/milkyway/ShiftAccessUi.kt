package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
internal fun ShiftAccessDialog(shift: Int, onUnlock: () -> Unit, onClose: () -> Unit) {
    // The selected shift may survive rotation; entered credentials never do.
    var pin by remember { mutableStateOf("") }
    var incorrect by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { pin = ""; incorrect = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(.92f).imePadding().testTag("shift-lock"),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Zmiana $shift", style = MaterialTheme.typography.headlineSmall)
                Text("Wpisz PIN, aby otworzyć panel zmiany.")
                OutlinedTextField(pin, {
                    pin = it.filter { digit -> digit in '0'..'9' }.take(4); incorrect = false
                }, label = { Text("PIN") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(), isError = incorrect,
                    modifier = Modifier.fillMaxWidth().testTag("shift-pin"))
                if (incorrect) Text("Nieprawidłowy PIN.", color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Anuluj") }
                    Button(onClick = {
                        val submitted = pin; pin = ""
                        if (warehousePinMatches(submitted)) onUnlock() else incorrect = true
                    }, enabled = pin.length == 4, modifier = Modifier.testTag("shift-unlock")) { Text("Otwórz") }
                }
            }
        }
    }
}
