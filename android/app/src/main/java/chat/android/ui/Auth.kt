package chat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.LaunchedEffect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import chat.android.BiometricHelper
import chat.android.PinHelper
import androidx.compose.material3.HorizontalDivider
import chat.android.SecureBlobStore
import chat.engine.baseUrl
import chat.engine.normalizeServer

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, hint: String? = null, tag: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = hint?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
fun TransportWarning(server: String) {
    if (baseUrl(server).startsWith("http://") && !server.startsWith("localhost")) {
        Text(
            "⚠ Unverschlüsselte Verbindung (http): Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber der Verkehr ist im Netzwerk sichtbar. Nur in vertrauenswürdigen Netzen nutzen.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Erster Start: drei klare Wege (neues Konto, per QR-Code, per Backup-Datei), danach das passende Formular. */
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var mode by remember { mutableStateOf<String?>(null) } // null | register | qr | backup
    androidx.activity.compose.BackHandler(enabled = mode != null) { mode = null }
    if (mode == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Forum, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Text("Willkommen bei Chat", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ende-zu-Ende-verschlüsselt, ohne Telefonnummer, auf deinem eigenen Server. Deine Schlüssel entstehen auf diesem Gerät und verlassen es nie.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { mode = "register" }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("onb_register")) { Text("Neues Konto erstellen") }
            OutlinedButton(onClick = { mode = "qr" }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("onb_qr")) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Mit QR-Code anmelden")
            }
            OutlinedButton(onClick = { mode = "backup" }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("onb_backup")) {
                Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(8.dp)); Text("Mit Backup-Datei anmelden")
            }
            Text(
                "QR-Code: im Webinterface oder auf einem anderen Gerät unter Einstellungen → Geräte anzeigen lassen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    } else AccountForm(vm, mode!!, onBack = { mode = null })
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AccountForm(vm: AppViewModel, mode: String, onBack: () -> Unit) {
    var server by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var qrLink by remember { mutableStateOf<String?>(null) }
    var probe by remember { mutableStateOf("") }
    var probeOk by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var backupUri by remember { mutableStateOf<Uri?>(null) }
    val scan = rememberQrScanner("Anmelde-QR-Code scannen") { text -> qrLink = text; err = "" }
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { backupUri = it }

    // Server automatisch prüfen (Registrierungsmodus, Mindestlänge der Passphrase)
    LaunchedEffect(server) {
        probeOk = null
        if (mode != "register" || server.isBlank()) { probe = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(700)
        probe = "Prüfe Server …"
        runCatching { vm.engine.probeServer(server) }.onSuccess { i ->
            if (i.min_passphrase > 0) vm.prefs.minPassphrase = i.min_passphrase
            probeOk = i.registration
            probe = "✓ ${i.domain}" + (i.app_version?.let { " · v$it" } ?: "") + " · Registrierung " + when (i.registration) { "open" -> "offen"; "invite" -> "mit Einladungscode"; else -> "geschlossen" }
        }.onFailure { probe = "✗ ${it.message}" }
    }

    Scaffold(topBar = {
        androidx.compose.material3.TopAppBar(
            title = { Text(when (mode) { "register" -> "Neues Konto"; "qr" -> "Mit QR-Code anmelden"; else -> "Mit Backup anmelden" }) },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (mode) {
                "register" -> {
                    Field("Server", server, { server = it }, hint = "chat.example.org oder 192.168.1.10:8080", tag = "reg_server")
                    if (probe.isNotEmpty()) Text(probe, style = MaterialTheme.typography.bodySmall, color = if (probe.startsWith("✗")) MaterialTheme.colorScheme.error else levelColor(Level.Ok))
                    TransportWarning(normalizeServer(server))
                    Field("Benutzername", name, { name = it.lowercase().filter { c -> !c.isWhitespace() } }, tag = "reg_name")
                    if (probeOk != "open") Field("Einladungscode", invite, { invite = it.trim() }, hint = "vom Betreiber; der erste Nutzer findet ihn im Server-Log", tag = "reg_invite")
                }
                "qr" -> {
                    Text("Öffne im Webinterface (oder in der App auf einem anderen Gerät) Einstellungen → Geräte → „QR-Code anzeigen“ und scanne ihn. Er gilt 5 Minuten und nur einmal.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = scan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(if (qrLink == null) "QR-Code scannen" else "QR-Code gelesen ✓ – erneut scannen") }
                }
                else -> {
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (backupUri == null) "Backup-Datei wählen" else "Backup-Datei gewählt ✓") }
                    Field("Passphrase des Backups", backupPass, { backupPass = it }, password = true)
                }
            }
            Text(if (mode == "register") "Passphrase" else "Neue Passphrase für dieses Gerät", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Sie verschlüsselt deine Daten auf diesem Gerät. Mindestens ${vm.prefs.minPassphrase} Zeichen (Vorgabe des Servers). Später kannst du zusätzlich eine PIN oder den Fingerabdruck einrichten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Field("Passphrase", pass, { pass = it }, password = true, tag = "reg_pass")
            Field("Passphrase wiederholen", pass2, { pass2 = it }, password = true, tag = "reg_pass2")
            Text("Es gibt kein „Passwort vergessen“. Ohne Passphrase und ohne Backup ist dein Konto verloren.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("reg_submit"),
                onClick = {
                    err = ""
                    if (pass.length < vm.prefs.minPassphrase) { err = "Die Passphrase braucht mindestens ${vm.prefs.minPassphrase} Zeichen."; return@Button }
                    if (pass != pass2) { err = "Die Passphrasen stimmen nicht überein."; return@Button }
                    busy = true
                    vm.run(onError = { err = it; busy = false }) {
                        when (mode) {
                            "qr" -> vm.engine.linkFromQr(qrLink ?: throw IllegalStateException("Bitte zuerst den QR-Code scannen."), pass)
                            "backup" -> {
                                val uri = backupUri ?: throw IllegalStateException("Bitte Backup-Datei wählen.")
                                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Datei nicht lesbar.")
                                vm.engine.linkDevice(bytes, backupPass, pass)
                            }
                            else -> vm.engine.createAccount(normalizeServer(server), name, invite, pass)
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Bitte warten …" else if (mode == "register") "Konto erstellen" else "Gerät anmelden") }
        }
    }
}

/** Entsperren: PIN-Tastenfeld (falls eingerichtet), Fingerabdruck/Gerätesperre, Passphrase als Rückfallebene. */
@Composable
fun UnlockScreen(vm: AppViewModel, activity: FragmentActivity) {
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val bio = remember { BiometricHelper.isEnrolled(activity) && BiometricHelper.available(activity) }
    var pinOn by remember { mutableStateOf(PinHelper.isEnrolled(activity)) }
    var usePass by remember { mutableStateOf(!pinOn) }
    var pin by remember { mutableStateOf("") }

    fun unlock(p: String) {
        busy = true
        err = ""
        vm.run(onError = { err = it; busy = false }) { vm.engine.unlock(p); busy = false }
    }
    fun bioPrompt() = BiometricHelper.unlock(activity) { r -> r.onSuccess { unlock(it) }.onFailure { err = "Biometrie abgebrochen oder fehlgeschlagen." } }
    LaunchedEffect(Unit) {
        // Nach dem Sperren per Knopf nicht sofort wieder abfragen (sonst wirkt „Sperren“ wirkungslos); beim Öffnen der App schon.
        if (bio && !vm.lockedManually) bioPrompt()
        vm.lockedManually = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(84.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Text("Chat ist gesperrt", style = MaterialTheme.typography.headlineSmall)
        Text(vm.engine.knownAddress() ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) androidx.compose.material3.CircularProgressIndicator()
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (pinOn && !usePass) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp).testTag("pin_dots")) {
                repeat(maxOf(pin.length, PinHelper.MIN_LEN)) { i ->
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp)) {}
                }
            }
            PinPad(
                enabled = !busy,
                onDigit = { d -> if (pin.length < PinHelper.MAX_LEN) { pin += d; err = "" } },
                onDelete = { pin = pin.dropLast(1) },
                onOk = {
                    val entered = pin
                    busy = true; err = ""
                    vm.run(onError = { err = it; busy = false }) {
                        // PBKDF2 (310 000 Runden) dauert auf dem Handy spürbar: nicht im UI-Thread rechnen.
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PinHelper.unlock(activity, entered) }
                        pin = ""
                        r.onSuccess { vm.engine.unlock(it); busy = false }
                            .onFailure { err = it.message ?: "PIN falsch."; busy = false; pinOn = PinHelper.isEnrolled(activity); if (!pinOn) usePass = true }
                    }
                },
                okEnabled = pin.length >= PinHelper.MIN_LEN,
            )
            TextButton(onClick = { usePass = true }) { Text("Mit Passphrase entsperren") }
        } else {
            Field("Passphrase", pass, { pass = it }, password = true, tag = "unlock_pass")
            Button(enabled = !busy && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("unlock_submit"), onClick = { unlock(pass) }) {
                Text(if (busy) "Entsperre …" else "Entsperren")
            }
            if (pinOn) TextButton(onClick = { usePass = false }) { Text("Mit PIN entsperren") }
        }
        if (bio) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { bioPrompt() }) {
            Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Fingerabdruck / Gerätesperre")
        }
    }
}

/** Ziffernfeld für die App-PIN. */
@Composable
private fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onOk: () -> Unit, okEnabled: Boolean) {
    val keys = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('<', '0', 'k'))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { k ->
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (k == 'k') MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(72.dp).testTag("pin_key_$k").clickable(enabled = enabled && (k != 'k' || okEnabled)) {
                            when (k) { '<' -> onDelete(); 'k' -> onOk(); else -> onDigit(k) }
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (k) {
                                '<' -> Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Löschen")
                                'k' -> Icon(Icons.Filled.Check, contentDescription = "OK", tint = MaterialTheme.colorScheme.onPrimary)
                                else -> Text(k.toString(), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Beim Löschen des Kontos auch Keystore-Schlüssel und Biometrie entfernen. */
fun wipeSecrets(activity: FragmentActivity) {
    BiometricHelper.disable(activity)
    PinHelper.disable(activity)
    SecureBlobStore.destroyKey()
}
