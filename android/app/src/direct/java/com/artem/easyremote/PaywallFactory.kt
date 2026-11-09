package com.artem.easyremote

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Direct (GitHub) build: no limit, no billing. */
private object NoPaywall : Paywall {
    override val state: StateFlow<PaywallState> = MutableStateFlow(PaywallState(enabled = false))
    override fun refresh() {}
    override fun buy(activity: Activity) {}
    override fun consumeOne() {}
}

@Suppress("UNUSED_PARAMETER")
fun createPaywall(context: Context): Paywall = NoPaywall
