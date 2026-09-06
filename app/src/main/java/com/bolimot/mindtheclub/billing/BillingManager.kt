package com.bolimot.mindtheclub.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Google Play Billing wrapper for the single subscription:
 *
 *   - [PRODUCT_STANDARD] "mtc_standard", the monthly subscription that unlocks the app after
 *     the 30 day activation based trial.
 *
 * The current state is cached in preferences so it can be read synchronously and offline (the
 * access gate in BaseActivity), and refreshed from Play on every app start and every time
 * SubscriptionActivity opens. Nessun server: verified client side, consistent with the app's
 * open source "convenience" model.
 */
object BillingManager : PurchasesUpdatedListener {

    const val PRODUCT_STANDARD = "mtc_standard"

    // Product id of the removed second tier. Test purchases of it may still be active on tester
    // accounts for a while; they count as a normal subscription so those devices keep working.
    private const val LEGACY_PRODUCT_STEALTH = "mtc_stealth"

    private const val PREF_ENTITLEMENT = "mtc_entitlement" // none | standard

    /** UI listeners, notified on the main-thread-agnostic billing callbacks. */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile private var billingClient: BillingClient? = null
    @Volatile private var connected = false
    @Volatile var productDetails: Map<String, ProductDetails> = emptyMap()
        private set
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureConnected {
            refreshPurchases()
            // Prices are needed outside SubscriptionActivity too (trial dialog, onboarding,
            // Options row), so they are fetched at startup and cached.
            queryProducts()
        }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    // ---------------------------------------------------------------- state

    fun hasSubscription(context: Context): Boolean =
        when (getPreference(PREF_ENTITLEMENT, context)) {
            // "stealth" can still be cached from the removed two-tier model.
            "standard", "stealth" -> true
            else -> false
        }

    /**
     * Master gate for using the app: an active subscription, an unfinished trial, or a trial
     * that has not started yet (it starts at the first message).
     */
    fun hasAccess(context: Context): Boolean =
        BuildConfig.NO_PAY ||
                hasSubscription(context) ||
                TrialManager.state(context) != TrialManager.State.EXPIRED

    // ----------------------------------------------------------- connection

    private fun client(context: Context): BillingClient {
        billingClient?.let { return it }
        synchronized(this) {
            billingClient?.let { return it }
            val c = BillingClient.newBuilder(context.applicationContext)
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .build()
            billingClient = c
            return c
        }
    }

    private fun ensureConnected(onReady: () -> Unit) {
        val context = appContext ?: return
        val c = client(context)
        if (connected && c.isReady) {
            onReady()
            return
        }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connected = true
                    debugLine("BillingManager", "Billing connected")
                    onReady()
                } else {
                    debugLine("BillingManager", "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                debugLine("BillingManager", "Billing disconnected")
            }
        })
    }

    // -------------------------------------------------------------- queries

    /** Re-reads owned subscriptions from Play and updates the cached state. */
    fun refreshPurchases() {
        val context = appContext ?: return
        ensureConnected {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            client(context).queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    debugLine("BillingManager", "queryPurchases failed: ${result.debugMessage}")
                    return@queryPurchasesAsync
                }
                applyPurchases(purchases)
            }
        }
    }

    /** Loads ProductDetails (localized price) for the plan; notifies listeners. */
    fun queryProducts() {
        val context = appContext ?: return
        ensureConnected {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCT_STANDARD)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
                    )
                )
                .build()
            client(context).queryProductDetailsAsync(params) { result, detailsResult ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    debugLine("BillingManager", "queryProducts failed: ${result.debugMessage}")
                    return@queryProductDetailsAsync
                }
                productDetails = detailsResult.productDetailsList.associateBy { it.productId }
                debugLine("BillingManager", "Products loaded: ${productDetails.keys}")
                notifyListeners()
            }
        }
    }

    // ------------------------------------------------------------- purchase

    /** Starts the Play purchase flow for the subscription. */
    fun launchPurchase(activity: Activity, productId: String) {
        val context = appContext ?: return
        val details = productDetails[productId] ?: run {
            debugLine("BillingManager", "No ProductDetails for $productId yet")
            return
        }
        val offer = pickOffer(details) ?: run {
            debugLine("BillingManager", "No subscription offer for $productId")
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = client(context).launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            debugLine("BillingManager", "launchBillingFlow failed: ${result.debugMessage}")
        }
    }

    private fun pickOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = details.subscriptionOfferDetails ?: return null
        return offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.firstOrNull()
    }

    fun recurringPrice(productId: String): String? {
        val offer = productDetails[productId]?.subscriptionOfferDetails?.firstOrNull()
            ?: return null
        return offer.pricingPhases.pricingPhaseList
            .lastOrNull { it.priceAmountMicros > 0L }
            ?.formattedPrice
    }

    // ------------------------------------------------------------ callbacks

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            applyPurchases(purchases)
        } else {
            debugLine("BillingManager", "onPurchasesUpdated: ${result.responseCode} ${result.debugMessage}")
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        val context = appContext ?: return

        val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }

        // Acknowledge anything new. Play refunds unacknowledged subscriptions after 3 days.
        for (p in active) {
            if (!p.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.purchaseToken)
                    .build()
                client(context).acknowledgePurchase(params) { r ->
                    debugLine("BillingManager", "acknowledge ${p.products}: ${r.responseCode}")
                }
            }
        }

        val subscribed = active.any {
            PRODUCT_STANDARD in it.products || LEGACY_PRODUCT_STEALTH in it.products
        }

        setPreference(PREF_ENTITLEMENT, if (subscribed) "standard" else "none", context)
        debugLine("BillingManager", "Subscribed -> $subscribed")
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try { l() } catch (e: Exception) {
                debugLine("BillingManager", "listener error: ${e.message}")
            }
        }
    }
}
