package pl.apargb.milkyway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun MilkywayRoot() {
    val context = LocalContext.current
    val session = remember { CloudSession.get(context) }
    val state by session.state.collectAsState()
    when {
        !state.configured || state.uid != null && state.ready -> key(state.uid ?: "local") { MilkywayApp(state, session) }
        state.uid != null -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.number?.let { "Konto $it" } ?: "Sesja użytkownika", style = MaterialTheme.typography.headlineSmall)
            if (state.error == null) { CircularProgressIndicator(); Text("Pobieranie wspólnych danych…") }
            else { Text(state.error!!); Button(onClick = session::retry) { Text("Spróbuj ponownie") } }
            TextButton(onClick = session::signOut) { Text("Wyloguj") }
        }
        else -> CloudLoginPage(state, session::signIn)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudLoginPage(state: CloudSessionState, onSignIn: (String, String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("01") }
    // A password must never enter saved instance state, logs or application preferences.
    var password by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(36.dp))
        Text("Milkyway", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Wspólny dziennik produkcji", style = MaterialTheme.typography.titleMedium)
        Text("Wybierz swoje konto i wpisz hasło.")
        ExposedDropdownMenuBox(expanded, onExpandedChange = { if (!state.checking) expanded = it }) {
            OutlinedTextField(number, {}, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("login-account"),
                readOnly = true, enabled = !state.checking, label = { Text("Numer konta") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                operatorNumbers.forEach { item -> DropdownMenuItem(text = { Text("Konto $item") },
                    modifier = Modifier.testTag("login-account-$item"), onClick = { number = item; expanded = false }) }
            }
        }
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("login-password"),
            enabled = !state.checking, singleLine = true, label = { Text("Hasło") }, visualTransformation = PasswordVisualTransformation())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login-error")) }
        Button(onClick = { onSignIn(number, password); password = "" }, enabled = !state.checking && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("login-submit")) { Text(if (state.checking) "Logowanie…" else "Zaloguj") }
        Text("Konta tworzy administrator zakładu. Nie musisz podawać adresu e-mail.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CloudStatus(state: CloudSessionState, saving: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (state.connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null, Modifier.size(16.dp))
        Text(when {
            !state.configured -> "Dane na tym telefonie · Chmura czeka na konfigurację"
            saving && !state.connected -> "Oczekiwanie na internet i potwierdzenie zapisu…"
            saving -> "Zapisywanie do wspólnej bazy…"
            !state.connected -> "Konto ${state.number} · Brak internetu — podgląd zapisanych danych"
            else -> "Konto ${state.number} · Aktualizacja na żywo"
        }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("cloud-status"))
    }
}
