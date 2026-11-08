package com.artem.easyremote

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.artem.easyremote.ui.AddPcScreen
import com.artem.easyremote.ui.EasyRemoteTheme
import com.artem.easyremote.ui.PcListScreen
import com.artem.easyremote.ui.RemoteScreen
import com.artem.easyremote.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            EasyRemoteTheme {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "easyremote") {
            vm.showAdd()
            vm.pairFromLink(data.toString())
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && vm.interceptVolumeKeys()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                vm.controller.volumeSteps(if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val screen by vm.screen.collectAsState()
    val pcs by vm.pcs.collectAsState()

    BackHandler(enabled = screen !is Screen.List) { vm.back() }

    when (val s = screen) {
        is Screen.List -> PcListScreen(vm)
        is Screen.Add -> AddPcScreen(vm)
        is Screen.Settings -> SettingsScreen(vm)
        is Screen.Remote -> {
            val pc = pcs.firstOrNull { it.fingerprint == s.fingerprint }
            if (pc != null) {
                RemoteScreen(vm, pc)
            } else {
                LaunchedEffect(Unit) { vm.back() }
            }
        }
    }
}
