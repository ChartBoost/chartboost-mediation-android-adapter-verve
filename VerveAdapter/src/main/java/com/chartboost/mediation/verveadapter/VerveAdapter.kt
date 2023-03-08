/*
 * Copyright 2022-2023 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.verveadapter

import android.app.Application
import android.content.Context
import android.util.Size
import android.view.View
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.pubnative.lite.sdk.HyBid
import net.pubnative.lite.sdk.interstitial.HyBidInterstitialAd
import net.pubnative.lite.sdk.models.AdSize
import net.pubnative.lite.sdk.views.HyBidAdView
import net.pubnative.lite.sdk.views.HyBidBannerAdView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation Verve Adapter.
 */
class VerveAdapter : PartnerAdapter {

    companion object {
        /**
         *
         */
        var testMode = HyBid.isTestMode()
            set(value) {
                field = value
                HyBid.setTestMode(value)
                PartnerLogController.log(
                    CUSTOM,
                    "HyBid test mode is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Key for parsing the Pangle SDK application ID.
         */
        private const val APP_TOKEN_KEY = "app_token"
    }

    /**
     * Get the HyBid SDK version.
     */
    override val partnerSdkVersion: String
        get() = HyBid.getHyBidVersion()

    /**
     * Get the Verve adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_VERVE_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "verve"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Verve"

    /**
     * Initialize the HyBid SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Unity Ads.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCoroutine { continuation ->
            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(APP_TOKEN_KEY))
                .trim()
                .takeIf { it.isNotEmpty() }?.let { app_token ->
                    (context.applicationContext as Application).let { application ->
                        HyBid.initialize(app_token, application) { success ->
                            when (success) {
                                true -> {
                                    continuation.resume(
                                        Result.success(
                                            PartnerLogController.log(SETUP_SUCCEEDED)
                                        )
                                    )
                                }
                                false -> {
                                    PartnerLogController.log(SETUP_FAILED)
                                    continuation.resume(
                                        Result.failure(
                                            ChartboostMediationAdException(
                                                ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing app token.")
                continuation.resumeWith(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS
                        )
                    )
                )
            }
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        // TODO: HyBid.getAppToken() may be the bidder info.
        return emptyMap()
    }

    /**
     * Attempt to load a Verve ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.BANNER -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL -> loadInterstitialAd(context, request, partnerAdListener)
            AdFormat.REWARDED -> loadRewardedAd(context, request, partnerAdListener)
        }
    }

    /**
     * Attempt to load a Verve banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val hyBidAdView = HyBidBannerAdView(context)
            val partnerAd = PartnerAd(
                ad = hyBidAdView,
                details = emptyMap(),
                request = request
            )
            hyBidAdView.setAdSize(getHyBidAdSize(request.size))
            hyBidAdView.load(
                request.partnerPlacement,
                object : HyBidAdView.Listener {
                    override fun onAdLoaded() {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        continuation.resume(
                            Result.success(partnerAd)
                        )
                    }

                    override fun onAdLoadFailed(error: Throwable?) {
                        error?.let {
                            it.message?.let { message ->
                                PartnerLogController.log(LOAD_FAILED, message)
                            }
                            continuation.resume(
                                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_EXCEPTION))
                            )
                        }
                    }

                    override fun onAdImpression() {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        listener.onPartnerAdImpression(partnerAd)
                    }

                    override fun onAdClick() {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(partnerAd)
                    }
                }
            )
        }
    }

    /**
     * Find the most appropriate HyBid ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The HyBid ad size that best matches the given [Size].
     */
    private fun getHyBidAdSize(size: Size?): AdSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> AdSize.SIZE_320x50
                it in 90 until 250 -> AdSize.SIZE_728x90
                it >= 250 -> AdSize.SIZE_300x250
                else -> AdSize.SIZE_320x50
            }
        } ?: AdSize.SIZE_320x50
    }

    /**
     * Attempt to load a Verve interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->

            val hyBidInterstitialAd = HyBidInterstitialAd(
                context,
                request.partnerPlacement,
                object : HyBidInterstitialAd.Listener {
                    override fun onInterstitialLoaded() {
                        // May need to create a router.
                    }

                    override fun onInterstitialLoadFailed(error: Throwable?) {
                        TODO("Not yet implemented")
                    }

                    override fun onInterstitialImpression() {
                        TODO("Not yet implemented")
                    }

                    override fun onInterstitialDismissed() {
                        TODO("Not yet implemented")
                    }

                    override fun onInterstitialClick() {
                        TODO("Not yet implemented")
                    }

                }
            )
        }
    }

    /**
     * Attempt to load a Verve rewarded ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->

        }
    }

    /**
     * Attempt to show the currently loaded Verve ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        TODO("Not yet implemented")
    }

    /**
     * Discard unnecessary Verve ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        // Only invalidate banners as there are no explicit methods to invalidate the other formats.
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Destroy the current Verve banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the Verve ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is HyBidAdView) {
                it.visibility = View.GONE
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an AdView.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Notify the HyBid SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )
        TODO("Not yet implemented")
    }

    /**
     * Notify Verve of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String
    ) {
        TODO("Not yet implemented")
    }

    /**
     * Notify Verve of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )
        HyBid.setCoppaEnabled(isSubjectToCoppa)
    }
}
