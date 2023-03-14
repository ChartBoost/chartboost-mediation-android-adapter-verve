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
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.pubnative.lite.sdk.HyBid
import net.pubnative.lite.sdk.interstitial.HyBidInterstitialAd
import net.pubnative.lite.sdk.models.AdSize
import net.pubnative.lite.sdk.rewarded.HyBidRewardedAd
import net.pubnative.lite.sdk.utils.Logger
import net.pubnative.lite.sdk.views.HyBidAdView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation Verve Adapter.
 */
class VerveAdapter : PartnerAdapter {

    companion object {
        /**
         * Test mode option that can be set to enabled to test HyBid SDK integrations.
         */
        var testModeEnabled = HyBid.isTestMode()
            set(value) {
                field = value
                HyBid.setTestMode(value)
                PartnerLogController.log(
                    CUSTOM,
                    "HyBid test mode is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Log level option that can be set to alter the output verbosity of the HyBid SDK.
         */
        var logLevel = Logger.Level.info
            set(value) {
                field = value
                HyBid.setLogLevel(value)
                PartnerLogController.log(CUSTOM, "HyBid log level set to $value.")
            }

        /**
         * Key for parsing the HyBid SDK app token.
         */
        private const val APP_TOKEN_KEY = "app_token"
    }

    /**
     * A map of Verve HyBidAdInterstitial ads for the corresponding Chartboost Mediation
     * load id.
     */
    private val hyBidInterstitialAdMap = mutableMapOf<String, HyBidInterstitialAd>()

    /**
     * A map of Verve HyBidRewardedAd ads for the corresponding Chartboost Mediation
     * load id.
     */
    private val hyBidRewardedAdMap = mutableMapOf<String, HyBidRewardedAd>()

    /**
     * Lambda to be called for a successful Verve ad dismiss.
     */
    private var onShowSuccess: () -> Unit = {}

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
        hyBidInterstitialAdMap.clear()
        hyBidRewardedAdMap.clear()

        return suspendCoroutine { continuation ->
            Json.decodeFromJsonElement<String>(
                (partnerConfiguration.credentials as JsonObject).getValue(APP_TOKEN_KEY)
            )
                .trim()
                .takeIf { it.isNotEmpty() }?.let { appToken ->
                    (context.applicationContext as Application).let { application ->
                        HyBid.initialize(appToken, application) { success ->
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
     * Attempt to show the currently loaded Verve ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format) {
            AdFormat.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> showFullScreenAd(partnerAd)
        }
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

        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> destroyFullscreenAd(partnerAd)
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

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        if (HyBid.isInitialized() && applies == HyBid.getUserDataManager().gdprApplies()) {
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> HyBid.getUserDataManager().grantConsent()
                GdprConsentStatus.GDPR_CONSENT_DENIED -> HyBid.getUserDataManager().denyConsent()
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> {
                    // We don't know the consent, let's have HyBid ask and show their consent screen.
                    if (HyBid.getUserDataManager().shouldAskConsent()) {
                        val intent = HyBid.getUserDataManager().getConsentScreenIntent(context)
                        context.startActivity(intent)
                    }
                }
            }
        }
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
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )
        if (HyBid.isInitialized()) HyBid.getUserDataManager().iabusPrivacyString = privacyString
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
        if (HyBid.isInitialized()) HyBid.setCoppaEnabled(isSubjectToCoppa)
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
            val hyBidAdView = HyBidAdView(context)
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
                        PartnerLogController.log(
                            LOAD_FAILED,
                            if (error != null) {
                                "Message: ${error.message}\n Cause:${error.cause}"
                            } else {
                                "Throwable error is null."
                            }
                        )
                        continuation.resume(
                            Result.failure(
                                ChartboostMediationAdException(
                                    ChartboostMediationError.CM_LOAD_FAILURE_EXCEPTION
                                )
                            )
                        )
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
            val partnerAd = PartnerAd(
                ad = request.identifier,
                details = emptyMap(),
                request = request
            )
            HyBidInterstitialAd(
                context,
                request.partnerPlacement,
                object : HyBidInterstitialAd.Listener {
                    override fun onInterstitialLoaded() {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        continuation.resume(
                            Result.success(partnerAd)
                        )
                    }

                    override fun onInterstitialLoadFailed(error: Throwable?) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            if (error != null) {
                                "Message: ${error.message}\n Cause:${error.cause}"
                            } else {
                                "Throwable error is null."
                            }
                        )
                        hyBidInterstitialAdMap.remove(partnerAd.request.identifier)
                        continuation.resume(
                            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_EXCEPTION))
                        )
                    }

                    override fun onInterstitialImpression() {
                        onShowSuccess()
                    }

                    override fun onInterstitialDismissed() {
                        PartnerLogController.log(DID_DISMISS)
                        hyBidInterstitialAdMap.remove(partnerAd.request.identifier)
                        listener.onPartnerAdDismissed(partnerAd, null)
                    }

                    override fun onInterstitialClick() {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(partnerAd)
                    }
                }
            ).also {
                hyBidInterstitialAdMap[request.identifier] = it
                it.load()
            }
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
            val partnerAd = PartnerAd(
                ad = request.identifier,
                details = emptyMap(),
                request = request
            )
            HyBidRewardedAd(
                context,
                request.partnerPlacement,
                object : HyBidRewardedAd.Listener {
                    override fun onRewardedLoaded() {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun onRewardedLoadFailed(error: Throwable?) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            if (error != null) {
                                "Message: ${error.message}\n Cause:${error.cause}"
                            } else {
                                "Throwable error is null."
                            }
                        )
                        hyBidRewardedAdMap.remove(partnerAd.request.identifier)
                        continuation.resume(
                            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_EXCEPTION))
                        )
                    }

                    override fun onRewardedOpened() {
                        onShowSuccess()
                    }

                    override fun onRewardedClosed() {
                        PartnerLogController.log(DID_DISMISS)
                        hyBidRewardedAdMap.remove(partnerAd.request.identifier)
                        listener.onPartnerAdDismissed(partnerAd, null)
                    }

                    override fun onRewardedClick() {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(partnerAd)
                    }

                    override fun onReward() {
                        PartnerLogController.log(DID_REWARD)
                        listener.onPartnerAdRewarded(partnerAd)
                    }
                }
            ).also {
                hyBidRewardedAdMap[request.identifier] = it
                it.load()
            }
        }
    }


    /**
     * Attempt to show a Verve fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullScreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            when (partnerAd.request.format) {
                AdFormat.INTERSTITIAL -> {
                    hyBidInterstitialAdMap[partnerAd.request.identifier]?.let {
                        if (it.isReady) {
                            it.show()
                        } else {
                            PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                            continuation.resume(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY
                                    )
                                )
                            )
                        }
                    } ?: run {
                        PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                        continuation.resume(
                            Result.failure(
                                ChartboostMediationAdException(
                                    ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND
                                )
                            )
                        )
                    }
                }
                AdFormat.REWARDED -> {
                    hyBidRewardedAdMap[partnerAd.request.identifier]?.let {
                        if (it.isReady) {
                            it.show()
                        } else {
                            PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                            continuation.resume(
                                Result.failure(
                                    ChartboostMediationAdException(
                                        ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY
                                    )
                                )
                            )
                        }
                    } ?: run {
                        PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                        continuation.resume(
                            Result.failure(
                                ChartboostMediationAdException(
                                    ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND
                                )
                            )
                        )
                    }
                }
                else -> {
                    PartnerLogController.log(SHOW_FAILED, "Ad is not a fullscreen ad.")
                    continuation.resume(
                        Result.failure(
                            ChartboostMediationAdException(
                                ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT
                            )
                        )
                    )
                }
            }

            onShowSuccess = {
                PartnerLogController.log(SHOW_SUCCEEDED)
                continuation.resume(Result.success(partnerAd))
            }

        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to destroy the current Verve fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return when (partnerAd.request.format) {
            AdFormat.INTERSTITIAL -> {
                hyBidInterstitialAdMap.remove(partnerAd.request.identifier)?.let {
                    it.destroy()
                    PartnerLogController.log(INVALIDATE_SUCCEEDED)
                    Result.success(partnerAd)
                } ?: run {
                    PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
                }
            }
            AdFormat.REWARDED -> {
                hyBidRewardedAdMap.remove(partnerAd.request.identifier)?.let {
                    it.destroy()
                    PartnerLogController.log(INVALIDATE_SUCCEEDED)
                    Result.success(partnerAd)
                } ?: run {
                    PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
                }
            }
            else -> {
                PartnerLogController.log(
                    INVALIDATE_FAILED,
                    "Ad is not HyBidInterstitialAd or HyBidRewardedAd."
                )
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
            }
        }
    }

    /**
     * Attempt to destroy the current Verve banner ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            (it as? HyBidAdView)?.run {
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } ?: run {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not a HyBidAdView.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }
}
