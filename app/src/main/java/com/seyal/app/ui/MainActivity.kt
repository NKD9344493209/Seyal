package com.seyal.app.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.seyal.app.ui.theme.SeyalTheme

class MainActivity : ComponentActivity() {
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    var openRunId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRunId.value = intent?.getStringExtra("run")
        val perms = mutableListOf(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPerms.launch(perms.toTypedArray())
        setContent { SeyalTheme { Shell(openRunId.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRunId.value = intent.getStringExtra("run")
    }

    fun askFor(vararg p: String) = askPerms.launch(arrayOf(*p))
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    RECIPES("Recipes", Icons.Outlined.List),
    ACTIVITY("Activity", Icons.Outlined.Timeline),
    CONTROL("Control", Icons.Outlined.Shield),
}

@Composable
fun Shell(openRunId: String?) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(openRunId) { tab = it }
                    Tab.RECIPES -> RecipesScreen()
                    Tab.ACTIVITY -> ActivityScreen(openRunId)
                    Tab.CONTROL -> ControlScreen()
                }
            }
            BottomBar(tab) { tab = it }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
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
