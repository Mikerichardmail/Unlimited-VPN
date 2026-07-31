package com.vpn.android.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        const val SKU_MONTHLY = "vpn_monthly"
        const val SKU_6MONTH = "vpn_6month"
        const val SKU_ANNUAL = "vpn_annual"
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

    private val _purchaseSuccessFlow = MutableSharedFlow<Pair<String, String>>() // Pair of (purchaseToken, planType)
    val purchaseSuccessFlow: SharedFlow<Pair<String, String>> = _purchaseSuccessFlow.asSharedFlow()

    init {
        startConnection()
    }

    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Billing client connected successfully
                }
            }

            override fun onBillingServiceDisconnected() {
                // Retry connection
                startConnection()
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

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList.first()
                val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: ""

                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()

                billingClient.launchBillingFlow(activity, billingFlowParams)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    val purchaseToken = purchase.purchaseToken
                    val productId = purchase.products.firstOrNull() ?: "yearly"
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        _purchaseSuccessFlow.emit(Pair(purchaseToken, productId))
                    }

                    if (!purchase.isAcknowledged) {
                        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(acknowledgePurchaseParams) {
                            // Purchase acknowledged
                        }
                    }
                }
            }
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
                onResult(emptyList())
            }
        }
    }
}
