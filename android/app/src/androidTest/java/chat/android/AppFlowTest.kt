package chat.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import chat.engine.ChannelPolicy
import chat.engine.Engine
import chat.engine.FileBlobStore
import chat.engine.Part
import chat.engine.baseUrl
import chat.engine.decodeCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ende-zu-Ende-Test der echten App auf dem Emulator gegen einen echten Server auf dem Host (10.0.2.2):
 * Registrierung, App-PIN einrichten, Sperren/Entsperren per PIN, Kanal von einem anderen Gerät desselben Kontos erscheint,
 * Chat-Anfrage annehmen, Nachrichten in beide Richtungen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(
        if (android.os.Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant(),
    ).around(compose)

    private val args = InstrumentationRegistry.getArguments()
    private val server = args.getString("chatServer") ?: "10.0.2.2:18200"
    private val adminKey = args.getString("chatAdminKey") ?: "emulator-admin-key"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as ChatApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun invite(): String {
        val c = URL("${baseUrl(server)}/v1/admin/invites").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.setRequestProperty("X-Admin-Key", adminKey); c.doOutput = true
        c.outputStream.use { it.write(ByteArray(0)) }
        val body = c.inputStream.bufferedReader().readText()
        return body.substringAfter("\"invite\"").substringAfter("\"").substringBefore("\"")
    }

    private fun waitTag(tag: String, ms: Long = 30_000) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), ms)
    private fun waitText(text: String, ms: Long = 30_000) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), ms)
    private fun scrollTo(tag: String) { runCatching { compose.onNodeWithTag(tag).performScrollTo() } }
    private fun type(tag: String, text: String) { waitTag(tag); scrollTo(tag); compose.onNodeWithTag(tag).performTextInput(text) }
    private fun click(tag: String) { waitTag(tag); scrollTo(tag); compose.onNodeWithTag(tag).performClick() }

    private fun until(what: String, ms: Long = 60_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(300) }
        throw AssertionError("Zeitüberschreitung: $what")
    }

    @Test
    fun fullFlow() {
        try { flow() } catch (e: Throwable) {
            // Bei Fehlern den sichtbaren UI-Baum mitliefern, damit man im CI-Log sieht, wo die App steht.
            val tree = runCatching { compose.onAllNodes(isRoot()).printToString(maxDepth = 30) }.getOrElse { "(kein UI-Baum: ${it.message})" }
            throw AssertionError("${e.message}\n--- UI ---\n$tree", e)
        }
    }

    private fun flow() {
        val pass = "emulator-pass-1"
        // 1. Registrierung über die Oberfläche
        click("onb_register")
        type("reg_server", server)
        type("reg_name", "alice")
        type("reg_invite", invite())
        type("reg_pass", pass)
        type("reg_pass2", pass)
        click("reg_submit")
        waitText("Backup speichern", 60_000)
        // Die Backup-Datei würde über den System-Dateidialog gespeichert; im Test nur als erledigt markieren.
        runBlocking { app.engine.markBackupDone() }

        // 2. Angebot „Schneller entsperren“ → App-PIN einrichten (mit Speichern-Knopf)
        waitText("Schneller entsperren?")
        compose.onNodeWithText("Jetzt einrichten").performClick()
        type("qu_pass", pass)
        type("qu_pin", "2468")
        type("qu_pin2", "2468")
        click("qu_pin_save")
        waitText("App-PIN gespeichert")
        // zurück: Unterseite → Einstellungen → Übersicht
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitTag("fab_add")

        // 3. Sperren-Knopf und Entsperren per PIN
        click("btn_lock")
        waitTag("pin_key_2")
        for (k in "2468") click("pin_key_$k")
        click("pin_key_k")
        waitTag("fab_add", 60_000)

        // 4. Ein anderes Gerät desselben Kontos (wie das Webinterface) legt einen Kanal an → erscheint in der App
        val backup = runBlocking { app.engine.exportBackup("emulator-backup-1") }
        val dev2 = Engine(FileBlobStore(File(ctx.cacheDir, "dev2-${System.nanoTime()}")), scope = scope)
        runBlocking {
            dev2.init()
            dev2.linkDevice(backup, "emulator-backup-1", "emulator-pass-2")
            dev2.createChannel("VomZweitgeraet", ChannelPolicy(join_mode = "open"))
        }
        click("tab_channels")
        waitText("VomZweitgeraet", 90_000)

        // 5. Ein anderer Nutzer startet einen Chat; die App nimmt an, Nachrichten in beide Richtungen
        val bob = Engine(FileBlobStore(File(ctx.cacheDir, "bob-${System.nanoTime()}")), scope = scope)
        val link = app.engine.contactLink(baseUrl(server))
        val convId = runBlocking {
            bob.init()
            bob.createAccount(server, "bob", invite(), "emulator-pass-3")
            bob.startChat(decodeCard(link))
        }
        click("tab_chats")
        waitText("bob@", 60_000)
        compose.onNodeWithText("bob@", substring = true).performClick()
        click("request_accept")
        runBlocking { bob.sendMessage(convId, text = "Hallo Alice") }
        waitText("Hallo Alice", 60_000)
        type("composer_input", "Hallo Bob")
        click("composer_send")
        until("Antwort kommt bei Bob an") {
            runBlocking { bob.snapshot() }!!.conversations[convId]!!.messages.any { m -> m.parts.any { it is Part.Text && it.body == "Hallo Bob" } }
        }
        dev2.close(); bob.close()
    }
}
