package me.ri3d.adb

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.ri3d.adb.car.CarState
import me.ri3d.adb.ui.AddAppScreen
import me.ri3d.adb.ui.BottomNav
import me.ri3d.adb.ui.C
import me.ri3d.adb.ui.CarHome
import me.ri3d.adb.ui.CommandsScreen
import me.ri3d.adb.ui.ConnectScreen
import me.ri3d.adb.ui.FilesScreen
import me.ri3d.adb.ui.HeadunitTheme
import me.ri3d.adb.ui.Snack
import me.ri3d.adb.ui.Tab

class MainActivity : ComponentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { HeadunitTheme { Root(app) } }
    }

    override fun onStart() {
        super.onStart()
        app.car.setForeground(true)
    }

    override fun onStop() {
        app.car.setForeground(false) { Ops.active.value.isNotEmpty() }
        super.onStop()
    }
}

@Composable
private fun Root(app: App) {
    val state by app.car.state.collectAsStateWithLifecycle()
    val connected = state is CarState.Connected
    var tab by rememberSaveable { mutableStateOf(Tab.Car) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val apps by app.apps.apps.collectAsStateWithLifecycle()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Files and Commands need the car. Without it the connect screen shows (with the reason),
    // and the chosen tab comes back once the car is reconnected.
    BackHandler(adding) { adding = false }
    NotificationPermission(app)

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        when {
            adding -> AddAppScreen(app, apps.firstOrNull { it.packageName == addTarget }) { adding = false }
            !connected -> ConnectScreen(app, state)
            tab == Tab.Files -> FilesScreen(app)
            tab == Tab.Commands -> CommandsScreen(app)
            else -> CarHome(app, (state as CarState.Connected).info) { target ->
                addTarget = target?.packageName
                app.apps.unpick()
                adding = true
            }
        }
        if (!adding && !imeVisible) {
            BottomNav(if (connected) tab else Tab.Car, connected, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp))
        }
        SnackHost(app, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = if (adding) 24.dp else 96.dp))
    }
}

@Composable
private fun SnackHost(app: App, modifier: Modifier) {
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        app.messages.collect { msg ->
            current = msg
            delay(3_600)
            current = null
            delay(200)
        }
    }
    current?.let { Snack(it, modifier) }
}

/** Asks once, at the first connection, so the connection/transfer notification can be shown (Android 13+). */
@Composable
private fun NotificationPermission(app: App) {
    val connected = app.car.state.collectAsStateWithLifecycle().value is CarState.Connected
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(connected) {
        if (connected && Build.VERSION.SDK_INT >= 33 && !app.settings.notificationsAsked &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            app.settings.notificationsAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
