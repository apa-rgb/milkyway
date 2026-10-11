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
        state.uid != null && state.requiresPasswordChange -> CloudPasswordPage(state, session::changePassword, session::signOut)
        !state.configured || state.uid != null && state.ready -> key(state.uid ?: "local") { MilkywayApp(state, session) }
        state.uid != null -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.number?.let { "Konto $it" } ?: "Sesja użytkownika", style = MaterialTheme.typography.headlineSmall)
            if (state.error == null) { CircularProgressIndicator(); Text("Pobieranie wspólnych danych…") }
            else { Text(state.error!!); Button(onClick = session::retry) { Text("Spróbuj ponownie") } }
            TextButton(onClick = session::signOut) { Text("Wyloguj") }
        }
        else -> CloudLoginPage(state, session::resetPassword, session::savedEmail, session::signIn)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudLoginPage(state: CloudSessionState,
    onReset: ((String, (String) -> Unit) -> Unit)? = null,
    savedEmail: (String) -> String = { "" },
    onSignIn: (String, String, String, String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("01") }
    var name by rememberSaveable { mutableStateOf("") }
    // A password must never enter saved instance state, logs or application preferences.
    var password by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var resetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(number) { email = savedEmail(number) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(36.dp))
        Text("Milkyway", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Wspólny dziennik produkcji", style = MaterialTheme.typography.titleMedium)
        Text("Wybierz swoje konto i wpisz hasło. Możesz też podać imię widoczne przy notatkach.")
        ExposedDropdownMenuBox(expanded, onExpandedChange = { if (!state.checking) expanded = it }) {
            OutlinedTextField(operatorLogin(number), {}, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("login-account"),
                readOnly = true, enabled = !state.checking, label = { Text("Login (6 znaków)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                operatorNumbers.forEach { item -> DropdownMenuItem(text = { Text(operatorLogin(item)) },
                    modifier = Modifier.testTag("login-account-$item"), onClick = { number = item; expanded = false }) }
            }
        }
        OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth().testTag("login-name"),
            enabled = !state.checking, singleLine = true, label = { Text("Twoje imię (opcjonalnie)") })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("login-password"),
            enabled = !state.checking, singleLine = true, label = { Text("Hasło") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(email, { email = it.take(254) }, Modifier.fillMaxWidth().testTag("login-email"),
            enabled = !state.checking, singleLine = true, label = { Text("E-mail przypisany do konta (jeśli już podany)") },
            supportingText = { Text("Przy pierwszym logowaniu zostaw puste. Na nowym urządzeniu podaj raz; adres zostanie zapamiętany.") })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login-error")) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button(onClick = { onSignIn(number, password, normalizeOperatorName(name), email); password = "" }, enabled = !state.checking && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("login-submit")) { Text(if (state.checking) "Logowanie…" else "Zaloguj") }
        if (onReset != null) TextButton(onClick = { resetOpen = true }, enabled = !state.checking,
            modifier = Modifier.testTag("login-reset")) { Text("Nie pamiętam hasła") }
        Text("Po pierwszym logowaniu zmień hasło i potwierdź e-mail do odzyskiwania dostępu.", style = MaterialTheme.typography.bodySmall)
    }
    if (resetOpen && onReset != null) CloudResetDialog(email, onReset) { resetOpen = false }
}

@Composable
internal fun CloudResetDialog(initialEmail: String, onReset: (String, (String) -> Unit) -> Unit, onClose: () -> Unit) {
    var email by remember { mutableStateOf(initialEmail) }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!sending) onClose() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(.92f).imePadding(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reset hasła", style = MaterialTheme.typography.titleLarge)
                Text("Podaj potwierdzony e-mail przypisany do konta. Otrzymasz link do ustawienia nowego hasła.")
                OutlinedTextField(email, { email = it.take(254) }, singleLine = true, enabled = !sending,
                    label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth().testTag("reset-email"))
                message?.let { Text(it, Modifier.testTag("reset-message")) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, enabled = !sending) { Text("Zamknij") }
                    TextButton(onClick = { sending = true; message = null; onReset(email) { message = it; sending = false } },
                        enabled = !sending && validRecoveryEmail(email.trim()), modifier = Modifier.testTag("reset-send")) {
                        Text(if (sending) "Wysyłanie…" else "Wyślij link")
                    }
                }
            }
        }
    }
}

@Composable
internal fun CloudPasswordPage(state: CloudSessionState, onChange: (String, String, String, (String?) -> Unit) -> Unit, onCancel: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(state.recoveryEmail.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Zmień hasło · ${operatorLogin(state.number.orEmpty())}", style = MaterialTheme.typography.titleLarge)
        Text(if (state.requiresPasswordChange) "Ustaw własne hasło przed pierwszym użyciem konta." else "Hasło będzie obowiązywać na telefonach i komputerach.")
        Text("Hasło: dokładnie 8 liter i cyfr, co najmniej jedna litera i jedna cyfra. Potwierdź e-mail z wiadomości, aby włączyć reset hasła.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(current, { current = it }, Modifier.fillMaxWidth().testTag("password-current"), enabled = !sending,
            singleLine = true, label = { Text("Obecne hasło") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(next, { next = it.take(8) }, Modifier.fillMaxWidth().testTag("password-new"), enabled = !sending,
            singleLine = true, label = { Text("Nowe hasło (8 znaków)") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(repeated, { repeated = it.take(8) }, Modifier.fillMaxWidth().testTag("password-repeat"), enabled = !sending,
            singleLine = true, label = { Text("Powtórz nowe hasło") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(email, { email = it.take(254) }, Modifier.fillMaxWidth().testTag("password-email"), enabled = !sending,
            singleLine = true, label = { Text("E-mail do resetowania hasła") })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("password-error")) }
        Button(onClick = {
            sending = true; error = null
            onChange(current, next, email) { message -> current = ""; next = ""; repeated = ""; sending = false; error = message }
        }, enabled = !sending && current.isNotBlank() && next != current && validNewPassword(next) && next == repeated && validRecoveryEmail(email.trim()),
            modifier = Modifier.fillMaxWidth().testTag("password-save")) { Text(if (sending) "Zapisywanie…" else "Zmień hasło i zapisz e-mail") }
        TextButton(onClick = onCancel, enabled = !sending) { Text(if (state.requiresPasswordChange) "Wyloguj" else "Anuluj") }
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
            !state.connected -> "${state.noteAuthor ?: "Konto ${state.number}"} · Brak internetu — podgląd zapisanych danych"
            else -> "${state.noteAuthor ?: "Konto ${state.number}"} · Aktualizacja na żywo"
        }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("cloud-status"))
    }
}
