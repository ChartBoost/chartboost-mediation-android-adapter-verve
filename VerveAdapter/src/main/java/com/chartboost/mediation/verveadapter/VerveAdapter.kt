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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import net.pubnative.lite.sdk.HyBid
import net.pubnative.lite.sdk.HyBidError
import net.pubnative.lite.sdk.HyBidErrorCode
import net.pubnative.lite.sdk.interstitial.HyBidInterstitialAd
import net.pubnative.lite.sdk.models.AdSize
import net.pubnative.lite.sdk.rewarded.HyBidRewardedAd
import net.pubnative.lite.sdk.utils.Logger
import net.pubnative.lite.sdk.views.HyBidAdView
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation Verve Adapter.
 */
class VerveAdapter : PartnerAdapter {

    companion object {
        /**
         * Test mode option that can be enabled to test Verve SDK integrations.
         */
        var testModeEnabled = HyBid.isTestMode()
            set(value) {
                HyBid.setTestMode(value)
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Verve SDK test mode is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Log level option that can be set to alter the output verbosity.
         */
        var logLevel = Logger.Level.info
            set(value) {
                HyBid.setLogLevel(value)
                field = value
                PartnerLogController.log(CUSTOM, "Verve SDK log level set to $value.")
            }

        /**
         * Key for parsing the Verve SDK app token.
         */
        private const val APP_TOKEN_KEY = "app_token"
    }

    /**
     * A map of Chartboost Mediation load identifiers with their corresponding Verve HyBidAdInterstitial ads.
     */
    private val loadIdToHyBidInterstitialAds = mutableMapOf<String, HyBidInterstitialAd>()

    /**
     * A map of Chartboost Mediation load identifiers with their corresponding Verve HyBidRewardedAd ads.
     */
    private val loadIdToHyBidRewardedAds = mutableMapOf<String, HyBidRewardedAd>()

    /**
     * Get the Verve SDK version.
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
     * Initialize the Verve SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Verve.
     *
     * @return Result.success(Unit) if Verve successfully initialized, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)
        loadIdToHyBidInterstitialAds.clear()
        loadIdToHyBidRewardedAds.clear()

        return suspendCoroutine { continuation ->
            Json.decodeFromJsonElement<String>((partnerConfiguration.credentials as JsonObject).getValue(APP_TOKEN_KEY))
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { appToken ->
                    HyBid.initialize(
                        appToken,
                        context.applicationContext as Application
                    ) { success ->
                        when (success) {
                            true -> continuation.resume(Result.success(PartnerLogController.log(SETUP_SUCCEEDED)))
                            false -> {
                                PartnerLogController.log(SETUP_FAILED)
                                continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN)))
                            }
                        }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing app token.")
                continuation.resumeWith(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS)))
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

        return withContext(Dispatchers.IO) {
            val token = HyBid.getAppToken() ?: ""
            PartnerLogController.log(if (token.isEmpty()) BIDDER_INFO_FETCH_FAILED else BIDDER_INFO_FETCH_SUCCEEDED)
            mapOf("app_auth_token" to token)
        }
    }

    /**
     * Attempt to load a Verve ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
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
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> loadFullScreenAd(context, request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
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
            AdFormat.INTERSTITIAL -> showFullscreenAd(
                loadIdToHyBidInterstitialAds[partnerAd.request.identifier],
                partnerAd
            )
            AdFormat.REWARDED -> showFullscreenAd(
                loadIdToHyBidRewardedAds[partnerAd.request.identifier],
                partnerAd
            )
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
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
            AdFormat.INTERSTITIAL -> destroyFullscreenAd(
                loadIdToHyBidInterstitialAds.remove(partnerAd.request.identifier),
                partnerAd
            )
            AdFormat.REWARDED -> destroyFullscreenAd(
                loadIdToHyBidRewardedAds.remove(partnerAd.request.identifier),
                partnerAd
            )
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Notify the Verve SDK of the GDPR applicability and consent status.
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
                else -> {}
            }
        } else {
            PartnerLogController.log(CUSTOM, "Cannot set GDPR: The HyBid SDK is not initialized.")
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
        if (HyBid.isInitialized()) {
            HyBid.getUserDataManager().iabusPrivacyString = privacyString
        } else {
            PartnerLogController.log(CUSTOM, "Cannot set CCPA: The HyBid SDK is not initialized.")
        }
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
        if (HyBid.isInitialized()) {
            HyBid.setCoppaEnabled(isSubjectToCoppa)
        } else {
            PartnerLogController.log(CUSTOM, "Cannot set COPPA: The HyBid SDK is not initialized.")
        }
    }

    /**
     * Attempt to load a Verve banner ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val hyBidAdView = HyBidAdView(context)
            hyBidAdView.setAdSize(getHyBidAdSize(request.size))
            val hyBidAdViewListener = object : HyBidAdView.Listener {
                val partnerAd = PartnerAd(
                    ad = hyBidAdView,
                    details = emptyMap(),
                    request = request
                )

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
                            "Message: ${error.message}\nCause:${error.cause}"
                        } else {
                            "Throwable error is null."
                        }
                    )

                    continuation.resume(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
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

            if (request.adm.isNullOrEmpty()) {
                hyBidAdView.load(request.partnerPlacement, hyBidAdViewListener)
            } else {
                hyBidAdView.renderCustomMarkup(request.adm, hyBidAdViewListener)
            }
        }
    }

    /**
     * Find the most appropriate Verve ad size for the given screen area based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The Verve ad size that best matches the given [Size].
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
     * Attempt to load a Verve fullscreen ad.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullScreenAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            when(request.format) {
                AdFormat.INTERSTITIAL -> {
                    HyBidInterstitialAd(
                        context,
                        request.partnerPlacement,
                        buildInterstitialAdListener(request, listener, continuation)
                    ).also {
                        loadIdToHyBidInterstitialAds[request.identifier] = it
                        if (request.adm.isNullOrEmpty()) {
                            it.load()
                        } else {
                            it.prepareCustomMarkup(request.adm)
                        }
                    }
                }
                AdFormat.REWARDED -> {
                    HyBidRewardedAd(
                        context,
                        request.partnerPlacement,
                        buildRewardedAdListener(request, listener, continuation)
                    ).also {
                        loadIdToHyBidRewardedAds[request.identifier] = it
                        if (request.adm.isNullOrEmpty()) {
                            it.load()
                        } else {
                            it.prepareCustomMarkup(request.adm)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Build a [HyBidInterstitialAd.Listener] listener and return it.
     *
     * @param request The [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [Continuation] to notify Chartboost Mediation of load success or failure.
     *
     * @return A built [HyBidInterstitialAd.Listener] listener.
     */
    private fun buildInterstitialAdListener(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
        continuation: Continuation<Result<PartnerAd>>
    ) = object : HyBidInterstitialAd.Listener {
        val partnerAd = PartnerAd(
            ad = request.identifier,
            details = emptyMap(),
            request = request
        )

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
                    "Message: ${error.message}\nCause:${error.cause}"
                } else {
                    "Throwable error is null."
                }
            )

            loadIdToHyBidInterstitialAds.remove(partnerAd.request.identifier)
            continuation.resume(
                Result.failure(ChartboostMediationAdException(getChartboostMediationError(error)))
            )
        }

        override fun onInterstitialImpression() {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            listener.onPartnerAdImpression(partnerAd)
        }

        override fun onInterstitialDismissed() {
            PartnerLogController.log(DID_DISMISS)
            listener.onPartnerAdDismissed(partnerAd, null)
        }

        override fun onInterstitialClick() {
            PartnerLogController.log(DID_CLICK)
            listener.onPartnerAdClicked(partnerAd)
        }
    }

    /**
     * Build a [HyBidRewardedAd.Listener] listener and return it.
     *
     * @param request The [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [Continuation] to notify Chartboost Mediation of load success or failure.
     *
     * @return A built [HyBidRewardedAd.Listener] listener.
     */
    private fun buildRewardedAdListener(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
        continuation: Continuation<Result<PartnerAd>>
    ) = object : HyBidRewardedAd.Listener {
        val partnerAd = PartnerAd(
            ad = request.identifier,
            details = emptyMap(),
            request = request
        )

        override fun onRewardedLoaded() {
            PartnerLogController.log(LOAD_SUCCEEDED)
            continuation.resume(Result.success(partnerAd))
        }

        override fun onRewardedLoadFailed(error: Throwable?) {
            PartnerLogController.log(
                LOAD_FAILED,
                if (error != null) {
                    "Message: ${error.message}\nCause:${error.cause}"
                } else {
                    "Throwable error is null."
                }
            )

            continuation.resume(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
        }

        override fun onRewardedOpened() {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            listener.onPartnerAdImpression(partnerAd)
        }

        override fun onRewardedClosed() {
            PartnerLogController.log(DID_DISMISS)
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

    /**
     * Attempt to show a Verve fullscreen ad.
     *
     * @param fullscreenAd The fullscreen ad that will be shown.
     * @param partnerAd The [PartnerAd] object containing the partner ad to report its show result.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showFullscreenAd(fullscreenAd: Any?, partnerAd: PartnerAd): Result<PartnerAd> {
        return when (fullscreenAd) {
            is HyBidInterstitialAd -> {
                if (fullscreenAd.isReady) {
                    fullscreenAd.show()
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    Result.success(partnerAd)
                } else {
                    PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
                }
            }
            is HyBidRewardedAd -> {
                if (fullscreenAd.isReady) {
                    fullscreenAd.show()
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    Result.success(partnerAd)
                } else {
                    PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
                }
            }
            else -> {
                PartnerLogController.log(SHOW_FAILED, "Ad is not HyBidInterstitial or HyBidRewarded.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to destroy the current Verve fullscreen ad.
     *
     * @param fullscreenAd The fullscreen ad that will be destroyed.
     * @param partnerAd The [PartnerAd] instance containing the partner ad to report its destroy result.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyFullscreenAd(fullscreenAd: Any?, partnerAd: PartnerAd): Result<PartnerAd> {
        return when (fullscreenAd) {
            is HyBidInterstitialAd -> {
                fullscreenAd.destroy()
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
            is HyBidRewardedAd -> {
                fullscreenAd.destroy()
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
            else -> {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not HyBidInterstitialAd or HyBidRewardedAd.")
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

    /**
     * Convert a given Verve error into a [ChartboostMediationError].
     *
     * @param error The Verve error code to convert.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: Throwable?) = if (error is HyBidError) {
        when (error.errorCode) {
            HyBidErrorCode.EXPIRED_AD -> ChartboostMediationError.CM_SHOW_FAILURE_AD_EXPIRED
            HyBidErrorCode.INTERNAL_ERROR -> ChartboostMediationError.CM_INTERNAL_ERROR
            HyBidErrorCode.INVALID_AD -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_AD_REQUEST
            HyBidErrorCode.INVALID_URL -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_AD_REQUEST
            HyBidErrorCode.INVALID_ZONE_ID -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT
            HyBidErrorCode.NOT_INITIALISED -> ChartboostMediationError.CM_LOAD_FAILURE_PARTNER_NOT_INITIALIZED
            HyBidErrorCode.UNKNOWN_ERROR -> ChartboostMediationError.CM_UNKNOWN_ERROR
            HyBidErrorCode.UNSUPPORTED_ASSET -> ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT

            HyBidErrorCode.ERROR_RENDERING_BANNER,
            HyBidErrorCode.ERROR_RENDERING_INTERSTITIAL,
            HyBidErrorCode.ERROR_RENDERING_REWARDED -> ChartboostMediationError.CM_SHOW_FAILURE_MEDIA_BROKEN

            HyBidErrorCode.VAST_PLAYER_ERROR,
            HyBidErrorCode.MRAID_PLAYER_ERROR -> ChartboostMediationError.CM_SHOW_FAILURE_VIDEO_PLAYER_ERROR

            HyBidErrorCode.AUCTION_NO_AD,
            HyBidErrorCode.NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL

            HyBidErrorCode.INVALID_ASSET,
            HyBidErrorCode.INVALID_SIGNAL_DATA,
            HyBidErrorCode.NULL_AD,
            HyBidErrorCode.PARSER_ERROR,
            HyBidErrorCode.SERVER_ERROR_PREFIX -> ChartboostMediationError.CM_PARTNER_ERROR

            else -> ChartboostMediationError.CM_UNKNOWN_ERROR
        }
    } else {
        ChartboostMediationError.CM_UNKNOWN_ERROR
    }
}
