package com.artem.easyremote

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Google Play build: one consumable in-app product, [PRODUCT_ID] (price set in Play Console, 100 UAH).
 *
 * A purchase that has not been consumed yet *is* the credit, so nothing is stored locally and the
 * credits survive reinstalling the app: Play remembers unconsumed purchases. The purchase is
 * consumed only after the extra PC has been paired. (Play refunds purchases that are neither
 * consumed nor acknowledged after 3 days.)
 */
private class PlayPaywall(context: Context) : Paywall, PurchasesUpdatedListener {
    private val _state = MutableStateFlow(PaywallState(enabled = true))
    override val state: StateFlow<PaywallState> = _state

    private var details: ProductDetails? = null
    private var owned: List<Purchase> = emptyList()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private fun update(block: (PaywallState) -> PaywallState) {
        _state.value = block(_state.value)
    }

    private fun whenReady(onFail: (String) -> Unit = {}, block: () -> Unit) {
        if (client.isReady) {
            block()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    block()
                } else {
                    onFail("Google Play Billing is not available (${result.responseCode}).")
                }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    override fun refresh() {
        whenReady(onFail = { m -> update { it.copy(message = m) } }) {
            loadDetails()
            loadOwned()
        }
    }

    private fun loadDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, queried ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details = queried.productDetailsList.firstOrNull()
                val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
                update { it.copy(price = price, message = if (details == null) "The product is not set up in Google Play yet." else it.message) }
            }
        }
    }

    private fun loadOwned() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val mine = purchases.filter { PRODUCT_ID in it.products }
                owned = mine.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                val pending = mine.any { it.purchaseState == Purchase.PurchaseState.PENDING }
                update {
                    it.copy(
                        credits = owned.size,
                        busy = false,
                        message = if (pending && owned.isEmpty()) "Your payment is pending. It will unlock when Google confirms it." else null,
                    )
                }
            }
        }
    }

    override fun buy(activity: Activity) {
        val d = details
        if (d == null) {
            refresh()
            update { it.copy(message = "Loading the price from Google Play… try again in a moment.") }
            return
        }
        update { it.copy(busy = true, message = null) }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build()
        val result = client.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            update { it.copy(busy = false, message = "Could not start the purchase (${result.responseCode}).") }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> loadOwned()
            BillingClient.BillingResponseCode.USER_CANCELED -> update { it.copy(busy = false) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> loadOwned()
            else -> update { it.copy(busy = false, message = "Purchase failed (${result.responseCode}).") }
        }
    }

    override fun consumeOne() {
        val purchase = owned.firstOrNull() ?: return
        whenReady {
            val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            client.consumeAsync(params) { _, _ -> loadOwned() }
        }
    }

    private companion object {
        const val PRODUCT_ID = "extra_pc"
    }
}

fun createPaywall(context: Context): Paywall = PlayPaywall(context)
