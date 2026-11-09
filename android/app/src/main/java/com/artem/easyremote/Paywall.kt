package com.artem.easyremote

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Paid-PC limit. The first PC is free; every additional PC needs one purchase ("credit") that is
 * spent when that PC has been paired successfully. Only the Google Play build enforces this
 * (see src/play); the direct/GitHub build uses [NoPaywall].
 */
data class PaywallState(
    /** false = no limit at all (direct build) */
    val enabled: Boolean = false,
    /** purchased but not yet used extra-PC credits */
    val credits: Int = 0,
    /** localized price from Google Play, e.g. "100,00 ₴"; null until loaded */
    val price: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

interface Paywall {
    val state: StateFlow<PaywallState>

    /** Reload price and owned credits from Google Play. */
    fun refresh()

    /** Start the Google Play purchase sheet for one extra-PC credit. */
    fun buy(activity: Activity)

    /** Spend one credit after an extra PC has been paired. */
    fun consumeOne()
}

object PairPolicy {
    const val FREE_PCS = 1

    /**
     * True when pairing needs a purchased credit: the limit is on, the free PC is already used, and
     * this is not a re-pair of a PC that is already in the list ([pinnedFingerprint] is only known
     * when pairing from a QR code).
     */
    fun needsCredit(limitEnabled: Boolean, paired: List<Pc>, pinnedFingerprint: String?): Boolean {
        if (!limitEnabled) return false
        if (paired.size < FREE_PCS) return false
        if (pinnedFingerprint != null && paired.any { it.fingerprint == pinnedFingerprint }) return false
        return true
    }
}
