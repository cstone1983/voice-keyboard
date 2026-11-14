/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 * Modified for Voice Keyboard: Google Play Billing removed.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.cloud

import android.app.Activity
import android.content.Context

/**
 * Stand-in for the Play Billing wrapper upstream uses to sell Dictate Cloud credit.
 *
 * This build talks only to the user's own server and is not distributed through Google Play, so
 * there is nothing to buy. The class keeps upstream's shape so [DictateCloud] compiles unchanged
 * apart from its imports, which keeps merges from upstream small. Every call reports that billing
 * is unavailable, which upstream already handles as "the shop is closed".
 */
class DictateCloudBilling(@Suppress("UNUSED_PARAMETER") context: Context) {

    sealed interface Outcome {
        data class Purchased(val purchases: List<Purchase>) : Outcome
        data object Cancelled : Outcome
        data class Failed(val code: Int, val message: String) : Outcome
    }

    suspend fun connect(): Int = BillingResponseCode.BILLING_UNAVAILABLE

    suspend fun products(): Products = Products(BillingResponseCode.BILLING_UNAVAILABLE, emptyList())

    data class Products(val code: Int, val details: List<ProductDetails>) {
        fun find(pack: DictateCloudPack): ProductDetails? =
            details.firstOrNull { it.productId == pack.productId }
    }

    suspend fun purchases(): List<Purchase> = emptyList()

    fun launch(
        @Suppress("UNUSED_PARAMETER") activity: Activity,
        @Suppress("UNUSED_PARAMETER") details: ProductDetails,
        @Suppress("UNUSED_PARAMETER") offerToken: String,
        @Suppress("UNUSED_PARAMETER") walletId: String,
    ): Int = BillingResponseCode.BILLING_UNAVAILABLE

    suspend fun awaitOutcome(): Outcome =
        Outcome.Failed(BillingResponseCode.BILLING_UNAVAILABLE, "Billing is not part of this build")

    suspend fun consume(@Suppress("UNUSED_PARAMETER") purchaseToken: String): Int =
        BillingResponseCode.BILLING_UNAVAILABLE

    fun close() = Unit

    companion object {
        fun offerTokenOf(details: ProductDetails): String? =
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
    }
}

/** Response codes with the same values as Play Billing's, so logged codes still mean the same. */
object BillingResponseCode {
    const val SERVICE_DISCONNECTED = -1
    const val OK = 0
    const val USER_CANCELED = 1
    const val SERVICE_UNAVAILABLE = 2
    const val BILLING_UNAVAILABLE = 3
    const val ITEM_UNAVAILABLE = 4
    const val ERROR = 6
}

/** Minimal stand-ins for the Play Billing types [DictateCloud] reads. Never instantiated here. */
class Purchase(
    val products: List<String>,
    val purchaseState: Int,
    val purchaseToken: String,
) {
    object PurchaseState {
        const val UNSPECIFIED_STATE = 0
        const val PURCHASED = 1
        const val PENDING = 2
    }
}

class ProductDetails(
    val productId: String,
    val name: String,
    val title: String,
    val description: String,
    val oneTimePurchaseOfferDetailsList: List<OneTimePurchaseOfferDetails>?,
) {
    class OneTimePurchaseOfferDetails(
        val formattedPrice: String,
        val priceAmountMicros: Long,
        val priceCurrencyCode: String,
        val offerToken: String,
    )
}
