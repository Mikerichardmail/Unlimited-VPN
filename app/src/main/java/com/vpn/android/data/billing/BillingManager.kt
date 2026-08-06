package com.vpn.android.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        const val SKU_MONTHLY = "vpn_monthly"
        const val SKU_6MONTH  = "vpn_6month"
        const val SKU_ANNUAL  = "vpn_annual"

        private const val TAG           = "BillingManager"
        private const val MAX_RETRIES   = 8
        private const val BASE_DELAY_MS = 1_000L   // 1 second
        private const val MAX_DELAY_MS  = 64_000L  // 64 seconds cap
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    // ✅ FIX ❶: Changed from Pair<String,String> to PendingPurchase so the caller
    //    can call acknowledgeIfVerified() only after server confirms the token.
    data class PendingPurchase(
        val purchaseToken: String,
        val productId: String,
        /** Call this only when server-side verify returns success=true */
        val acknowledgeIfVerified: () -> Unit
    )

    private val _purchaseSuccessFlow = MutableSharedFlow<PendingPurchase>()
    val purchaseSuccessFlow: SharedFlow<PendingPurchase> = _purchaseSuccessFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track retry count for exponential backoff
    private var retryCount = 0

    init {
        startConnection()
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        retryCount = 0
                        Log.d(TAG, "Billing connected successfully")
                    }
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                        // ✅ PBL 9: Covers OEM kids-mode / system-blocked Play Store
                        Log.w(TAG, "Billing unavailable: ${billingResult.debugMessage}")
                    }
                    else -> {
                        Log.e(TAG, "Billing setup failed [${billingResult.responseCode}]: ${billingResult.debugMessage}")
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                if (retryCount >= MAX_RETRIES) return
                val delayMs = minOf(BASE_DELAY_MS * (1L shl retryCount), MAX_DELAY_MS)
                retryCount++
                Log.d(TAG, "Billing disconnected — retry #$retryCount in ${delayMs}ms")
                scope.launch {
                    delay(delayMs)
                    if (!billingClient.isReady) startConnection()
                }
            }
        })
    }

    fun launchPurchaseFlow(activity: Activity, productId: String) {
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // ✅ PBL 9: queryProductDetailsAsync callback signature:
        //    (BillingResult, QueryProductDetailsResult) -> Unit
        //    The list is accessed via queryProductDetailsResult.productDetailsList
        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            val productDetailsList = queryProductDetailsResult.productDetailsList
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK
                && productDetailsList.isNotEmpty()
            ) {
                val productDetails = productDetailsList.first()
                val offerToken = productDetails.subscriptionOfferDetails
                    ?.firstOrNull()?.offerToken ?: ""

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .setOfferToken(offerToken)
                                .build()
                        )
                    )
                    .build()

                billingClient.launchBillingFlow(activity, billingFlowParams)
            } else {
                Log.w(TAG, "queryProductDetailsAsync failed [${billingResult.responseCode}]: ${billingResult.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        val purchaseToken = purchase.purchaseToken
                        val productId = purchase.products.firstOrNull() ?: "yearly"

                        // ✅ FIX ❶: Acknowledge only after server verifies the token.
                        val pending = PendingPurchase(
                            purchaseToken = purchaseToken,
                            productId     = productId,
                            acknowledgeIfVerified = {
                                if (!purchase.isAcknowledged) {
                                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                                        .setPurchaseToken(purchaseToken)
                                        .build()
                                    billingClient.acknowledgePurchase(ackParams) { result ->
                                        Log.d(TAG, "Acknowledge result: ${result.responseCode}")
                                    }
                                }
                            }
                        )

                        scope.launch { _purchaseSuccessFlow.emit(pending) }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.d(TAG, "Purchase cancelled by user")
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                Log.w(TAG, "Billing unavailable during purchase: ${billingResult.debugMessage}")
            else ->
                Log.e(TAG, "Purchase update error [${billingResult.responseCode}]: ${billingResult.debugMessage}")
        }
    }

    fun queryActivePurchases(onResult: (List<Purchase>) -> Unit) {
        if (!billingClient.isReady) {
            onResult(emptyList())
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                onResult(purchasesList)
            } else {
                Log.e(TAG, "queryPurchasesAsync failed [${billingResult.responseCode}]: ${billingResult.debugMessage}")
                onResult(emptyList())
            }
        }
    }

    /**
     * Queries Google Play for the real local prices of all 3 subscription SKUs.
     * Returns a map of productId -> formattedPrice (e.g. "vpn_monthly" -> "$4.99").
     * Falls back to an empty map if billing is not ready or query fails.
     */
    fun queryProductPrices(onResult: (Map<String, String>) -> Unit) {
        if (!billingClient.isReady) {
            startConnection()
            onResult(emptyMap())
            return
        }

        val productList = listOf(SKU_MONTHLY, SKU_6MONTH, SKU_ANNUAL).map { sku ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // ✅ PBL 9: queryProductDetailsAsync callback:
        //    (BillingResult, QueryProductDetailsResult) -> Unit
        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            val productDetailsList = queryProductDetailsResult.productDetailsList
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "queryProductPrices failed [${billingResult.responseCode}]: ${billingResult.debugMessage}")
                onResult(emptyMap())
                return@queryProductDetailsAsync
            }
            val priceMap = productDetailsList.associate { details ->
                val price = details
                    .subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.firstOrNull()
                    ?.formattedPrice ?: ""
                details.productId to price
            }
            onResult(priceMap)
        }
    }

}
