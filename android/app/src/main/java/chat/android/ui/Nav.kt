package chat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.engine.Phase

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(activity: FragmentActivity, onSecureChanged: () -> Unit, vm: AppViewModel = viewModel()) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    // Test-Tags auch für UI Automator sichtbar machen (Emulator-Test der Release-APK).
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
        when (phase) {
            Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Phase.NoAccount -> OnboardingScreen(vm)
            Phase.Locked -> UnlockScreen(vm, activity)
            Phase.Unlocked -> MainNav(vm, activity, onSecureChanged)
        }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { vm.clearError() }, confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@Composable
private fun MainNav(vm: AppViewModel, activity: FragmentActivity, onSecureChanged: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(online) { if (online) isAdmin = runCatching { vm.engine.isAdmin() }.getOrDefault(false) }
    LaunchedEffect(online, vm.engine.info) { vm.engine.info?.min_passphrase?.takeIf { it > 0 }?.let { vm.prefs.minPassphrase = it } }
    val s = state ?: return
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    val route = vm.route
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (targetState == Route.Home) (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 2 } + fadeOut())
            else (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
        },
        label = "nav",
    ) { r ->
        when (r) {
            Route.Home -> HomeScreen(vm, s, online, isAdmin)
            is Route.Chat -> s.conversations[r.id]?.let { ChatScreen(vm, s, it) } ?: Gone(vm)
            is Route.ConvInfo -> s.conversations[r.id]?.let { ConvInfoScreen(vm, s, it) } ?: Gone(vm)
            is Route.Channel -> s.channels[r.id]?.let { ChannelScreen(vm, s, it) } ?: Gone(vm)
            is Route.ChannelInfo -> s.channels[r.id]?.let { ChannelInfoScreen(vm, it) } ?: Gone(vm)
            is Route.Files -> FilesScreen(vm, s, r.convId, onBack = { vm.back() })
            Route.Settings -> SettingsScreen(vm, activity, s, onBack = { vm.back() }, onSecureChanged = onSecureChanged)
            Route.Admin -> AdminScreen(vm, onBack = { vm.back() })
        }
    }
    var asked by remember { mutableStateOf(vm.prefs.quickUnlockAsked) }
    fun ask() { asked = true; vm.prefs.quickUnlockAsked = true }
    if (!s.backupDone) BackupGate(vm, s)
    else if (!asked && !chat.android.PinHelper.isEnrolled(activity) && !chat.android.BiometricHelper.isEnrolled(activity)) {
        AlertDialog(
            onDismissRequest = { ask() },
            title = { Text("Schneller entsperren?") },
            text = { Text("Statt jedes Mal die Passphrase einzugeben, kannst du eine App-PIN oder deinen Fingerabdruck einrichten. Die Passphrase bleibt dein Hauptschlüssel.") },
            confirmButton = { TextButton(onClick = { ask(); vm.settingsPage = "security"; vm.go(Route.Settings) }) { Text("Jetzt einrichten") } },
            dismissButton = { TextButton(onClick = { ask() }) { Text("Später") } },
        )
    }
}

/** Ziel existiert nicht mehr (gelöscht, verlassen): zurück zur Übersicht. */
@Composable
private fun Gone(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.home() }
}
