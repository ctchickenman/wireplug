package org.metalmind.wireplug.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import org.metalmind.wireplug.WirePlugApp

sealed interface Screen {
    data object Status : Screen
    data object Settings : Screen
    data object Tunnel : Screen
    data object Home : Screen
    data object Forwards : Screen
    data class EditForward(val id: String?) : Screen
    data object Proxy : Screen
    data object Behaviour : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WirePlugApp
        setContent {
            WirePlugTheme { App(app) }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as WirePlugApp).syncService()
    }
}

@Composable
private fun App(app: WirePlugApp) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Status) }
    val go: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = back)
    val store = app.config
    when (val s = stack.last()) {
        Screen.Status -> StatusScreen(store, go)
        Screen.Settings -> SettingsScreen(store, go, back)
        Screen.Tunnel -> TunnelScreen(store, back)
        Screen.Home -> HomeNetworkScreen(store, back)
        Screen.Forwards -> ForwardsScreen(store, go, back)
        is Screen.EditForward -> EditForwardScreen(store, s.id, back)
        Screen.Proxy -> ProxyScreen(store, back)
        Screen.Behaviour -> BehaviourScreen(store, back)
    }
}
