package com.artem.easyremote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Google Play build: Play delivers the updates, so there is nothing to check here. */
private object PlayUpdater : Updater {
    override val supported = false
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    override fun check() {}
    override fun checkIfDue() {}
    override fun install(info: UpdateInfo) {}
}

@Suppress("UNUSED_PARAMETER")
fun createUpdater(context: Context, scope: CoroutineScope, settings: SettingsStore): Updater = PlayUpdater
