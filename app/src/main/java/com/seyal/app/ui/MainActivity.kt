package com.seyal.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seyal.app.data.Part
import com.seyal.app.data.Settings
import com.seyal.app.BuildConfig
import com.seyal.app.device.Images
import com.seyal.app.device.SeyalNotificationListener
import com.seyal.app.ui.theme.SeyalTheme

class MainActivity : ComponentActivity() {
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick.value = resumeTick.value + 1; com.seyal.app.device.Perms.answered() }
    /** Files the user picked with the + button in the chat, waiting to be sent with the next message. */
    val attachments = androidx.compose.runtime.mutableStateListOf<com.seyal.app.device.Attachment>()
    val attaching = mutableStateOf(false)
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        attaching.value = true
        val room = com.seyal.app.device.Attach.MAX_FILES - attachments.size
        Thread {
            val loaded = uris.take(room.coerceAtLeast(0)).map { com.seyal.app.device.Attach.load(applicationContext, it, com.seyal.app.data.Settings.provider) }
            runOnUiThread { attachments.addAll(loaded); attaching.value = false }
        }.start()
    }
    /** Opens the phone's file picker: photos, PDFs, videos, voice clips, documents. */
    fun attach() = runCatching { pickFiles.launch(arrayOf("image/*", "application/pdf", "video/*", "audio/*", "text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation")) }
    var openSessionId = mutableStateOf<String?>(null)
    /** Text shared into Seyal from another app (Share → Seyal, or select text → Ask Seyal). */
    val sharedText = mutableStateOf<String?>(null)
    /** A picture shared into Seyal. */
    val sharedImage = mutableStateOf<Part.Image?>(null)
    /** Goes up each time Seyal is opened as the assistant, from the tile or the shortcut: start listening. */
    val listenTick = mutableStateOf(0)
    /** A tab a notification asked for ("brain"); cleared once shown. */
    val openTab = mutableStateOf<String?>(null)
    /** The last listen request the Chat screen acted on. */
    var listenSeen = 0
    /** Goes up on every return to the app, so permission states on screen refresh. */
    val resumeTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && !Settings.onboarded) { Settings.onboarded = true; askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
        setContent { SeyalTheme { Shell(openSessionId.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onPause() {
        super.onPause()
        com.seyal.app.device.Perms.asker = null
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value = resumeTick.value + 1
        // While Seyal is on screen, a request that needs a permission can ask for it there and then.
        com.seyal.app.device.Perms.asker = { p -> askPerms.launch(p) }
        runCatching { com.seyal.app.device.ScreenWatch.check(this) }
        com.seyal.app.device.WatchService.sync(this)
        // Notification access is granted but the reader isn't running (some phones stop it after an update or a
        // kill): ask Android to start it again instead of making the user switch it off and on.
        if (BuildConfig.FLAVOR == "full" && !SeyalNotificationListener.connected &&
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        ) runCatching { android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(this, SeyalNotificationListener::class.java)) }
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        openSessionId.value = intent.getStringExtra("session")
        intent.getStringExtra("tab")?.let { openTab.value = it; intent.removeExtra("tab") }
        // "Call" on a Brain notification: answered here, with the app in front, because only then may the dialer open.
        intent.getStringExtra("brain")?.takeIf { it.isNotBlank() }?.let { id ->
            com.seyal.app.agent.BrainLoop.answer(this, id, intent.getStringExtra("answer") ?: "yes")
            intent.removeExtra("brain")
        }
        when (intent.action) {
            // A skill's home-screen button: run it and get out of the way.
            ACTION_RUN_SKILL -> {
                val skill = intent.getStringExtra("skill")?.let { com.seyal.app.agent.Skills.get(it) }
                if (skill != null) {
                    com.seyal.app.agent.Skills.run(skill.id)
                    android.widget.Toast.makeText(this, "Running ${skill.name}", android.widget.Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                } else android.widget.Toast.makeText(this, "That skill was deleted", android.widget.Toast.LENGTH_SHORT).show()
                intent.action = null
            }
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, ACTION_LISTEN -> listenTick.value = listenTick.value + 1
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { sharedText.value = it.take(6000) }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { sharedText.value = it.take(6000) }
                if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    if (uri != null) Thread { val img = Images.load(applicationContext, uri); runOnUiThread { sharedImage.value = img } }.start()
                }
            }
        }
    }

    fun askFor(vararg p: String) = askPerms.launch(arrayOf(*p))

    companion object {
        const val ACTION_LISTEN = "com.seyal.app.LISTEN"
        const val ACTION_RUN_SKILL = "com.seyal.app.RUN_SKILL"
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Chat", Icons.Outlined.Home),
    BRAIN("Brain", Icons.Outlined.Psychology),
    SKILLS("Skills", Icons.Outlined.List),
    HISTORY("Activity", Icons.Outlined.Timeline),
    CONTROL("Control", Icons.Outlined.Shield),
}

@Composable
fun Shell(openSessionId: String?) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var simple by rememberSaveable { mutableStateOf(Settings.simpleMode) }
    LaunchedEffect(openSessionId) { if (openSessionId != null) tab = Tab.HISTORY }
    // Anything shared into Seyal, or a "listen" request, belongs on the Chat tab.
    val activity = LocalContext.current as? MainActivity
    val listen = activity?.listenTick?.value ?: 0
    val hasShared = activity?.sharedText?.value != null || activity?.sharedImage?.value != null
    LaunchedEffect(listen, hasShared) { if (listen > 0 || hasShared) tab = Tab.HOME }
    // A tap on one of the Brain's notifications opens its tab.
    val wantTab = activity?.openTab?.value
    LaunchedEffect(wantTab) { if (wantTab == "brain") { tab = Tab.BRAIN; activity?.openTab?.value = null } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Bottom bar and composer stay above the navigation bar and the keyboard.
        // Simple mode: one big microphone and nothing else, until "Normal mode" is tapped.
        if (simple && openSessionId == null) Column(Modifier.fillMaxSize().navigationBarsPadding()) { SimpleScreen { simple = false } }
        else Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Column(Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> ChatScreen(openSessionId) { tab = it }
                    Tab.BRAIN -> BrainScreen()
                    Tab.SKILLS -> SkillsScreen { tab = it }
                    Tab.HISTORY -> HistoryScreen(openSessionId)
                    Tab.CONTROL -> ControlScreen { simple = true }
                }
            }
            BottomBar(tab) { tab = it }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.values().forEach { t ->
            val selected = t == current
            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.weight(1f).height(48.dp).clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.padding(top = 4.dp))
                Text(t.label, color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
