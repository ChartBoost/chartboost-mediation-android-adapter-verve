/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.verveadapter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Size
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
import net.pubnative.lite.sdk.models.ImpressionTrackingMethod
import net.pubnative.lite.sdk.rewarded.HyBidRewardedAd
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
         * Key for parsing the Verve SDK app token.
         */
        private const val APP_TOKEN_KEY = "app_token"
    }

    /**
     * The Verve adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = VerveAdapterConfiguration

    /**
     * A map of Chartboost Mediation load identifiers with their corresponding Verve HyBidAdInterstitial ads.
     */
    private val loadIdToHyBidInterstitialAds = mutableMapOf<String, HyBidInterstitialAd>()

    /**
     * A map of Chartboost Mediation load identifiers with their corresponding Verve HyBidRewardedAd ads.
     */
    private val loadIdToHyBidRewardedAds = mutableMapOf<String, HyBidRewardedAd>()

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
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
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
                        context.applicationContext as Application,
                    ) { success ->
                        when (success) {
                            true -> {
                                setConsents(context, partnerConfiguration.consents, partnerConfiguration.consents.keys)
                                PartnerLogController.log(SETUP_SUCCEEDED)
                                continuation.resume(Result.success(emptyMap()))
                            }
                            false -> {
                                PartnerLogController.log(SETUP_FAILED)
                                continuation.resume(
                                    Result.failure(
                                        ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown),
                                    ),
                                )
                            }
                        }
                    }
                } ?: run {
                PartnerLogController.log(SETUP_FAILED, "Missing app token.")
                continuation.resumeWith(
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials)),
                )
            }
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        return withContext(Dispatchers.IO) {
            val token = HyBid.getAppToken() ?: ""
            PartnerLogController.log(if (token.isEmpty()) BIDDER_INFO_FETCH_FAILED else BIDDER_INFO_FETCH_SUCCEEDED)
            Result.success(mapOf("app_auth_token" to token))
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
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            PartnerAdFormats.BANNER -> loadBannerAd(context, request, partnerAdListener)
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED -> loadFullscreenAd(context, request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded Verve ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        fun showAdIfReady(
            isReady: () -> Boolean,
            showAd: () -> Unit,
        ): Result<PartnerAd> {
            return if (isReady()) {
                showAd()
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
            }
        }

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            PartnerAdFormats.INTERSTITIAL -> {
                loadIdToHyBidInterstitialAds[partnerAd.request.identifier]?.let {
                    showAdIfReady(it::isReady, it::show)
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
                }
            }
            PartnerAdFormats.REWARDED -> {
                loadIdToHyBidRewardedAds[partnerAd.request.identifier]?.let {
                    showAdIfReady(it::isReady, it::show)
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
                }
            }
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED -> destroyFullscreenAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>
    ) {
        // Hybid automatically pulls consents directly from Shared Preferences
    }

    /**
     * Notify Verve if the user is underage.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is underage, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        if (HyBid.isInitialized()) {
            PartnerLogController.log(
                if (isUserUnderage) {
                    USER_IS_UNDERAGE
                } else {
                    USER_IS_NOT_UNDERAGE
                },
            )
            HyBid.setCoppaEnabled(isUserUnderage)
        } else {
            PartnerLogController.log(CUSTOM, "Cannot set isUserUnderage: The HyBid SDK is not initialized.")
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
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val hyBidAdView =
                HyBidAdView(context).apply {
                    setAdSize(getHyBidAdSize(request.bannerSize?.size))
                    setTrackingMethod(ImpressionTrackingMethod.AD_VIEWABLE)
                }
            val hyBidAdViewListener =
                object : HyBidAdView.Listener {
                    val partnerAd =
                        PartnerAd(
                            ad = hyBidAdView,
                            details = emptyMap(),
                            request = request,
                        )

                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.context.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onAdLoaded() {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(partnerAd))
                    }

                    override fun onAdLoadFailed(error: Throwable?) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            if (error != null) {
                                "Message: ${error.message}\nCause:${error.cause}"
                            } else {
                                "Throwable error is null."
                            },
                        )

                        resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
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
    private suspend fun loadFullscreenAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            when (request.format) {
                PartnerAdFormats.INTERSTITIAL -> {
                    HyBidInterstitialAd(
                        context,
                        request.partnerPlacement,
                        buildInterstitialAdListener(request, listener, continuation),
                    ).also {
                        loadIdToHyBidInterstitialAds[request.identifier] = it
                        if (request.adm.isNullOrEmpty()) {
                            it.load()
                        } else {
                            it.prepareCustomMarkup(request.adm)
                        }
                    }
                }
                PartnerAdFormats.REWARDED -> {
                    HyBidRewardedAd(
                        context,
                        request.partnerPlacement,
                        buildRewardedAdListener(request, listener, continuation),
                    ).also {
                        loadIdToHyBidRewardedAds[request.identifier] = it
                        if (request.adm.isNullOrEmpty()) {
                            it.load()
                        } else {
                            it.prepareCustomMarkup(request.adm)
                        }
                    }
                }
                else -> {
                    continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat)))
                }
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
        continuation: Continuation<Result<PartnerAd>>,
    ) = object : HyBidInterstitialAd.Listener {
        val partnerAd =
            PartnerAd(
                ad = request.identifier,
                details = emptyMap(),
                request = request,
            )

        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.context.isActive) {
                continuation.resume(result)
            }
        }

        override fun onInterstitialLoaded() {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(Result.success(partnerAd))
        }

        override fun onInterstitialLoadFailed(error: Throwable?) {
            PartnerLogController.log(
                LOAD_FAILED,
                if (error != null) {
                    "Message: ${error.message}\nCause:${error.cause}"
                } else {
                    "Throwable error is null."
                },
            )

            loadIdToHyBidInterstitialAds.remove(partnerAd.request.identifier)
            resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
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
        continuation: Continuation<Result<PartnerAd>>,
    ) = object : HyBidRewardedAd.Listener {
        val partnerAd =
            PartnerAd(
                ad = request.identifier,
                details = emptyMap(),
                request = request,
            )

        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.context.isActive) {
                continuation.resume(result)
            }
        }

        override fun onRewardedLoaded() {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(Result.success(partnerAd))
        }

        override fun onRewardedLoadFailed(error: Throwable?) {
            PartnerLogController.log(
                LOAD_FAILED,
                if (error != null) {
                    "Message: ${error.message}\nCause:${error.cause}"
                } else {
                    "Throwable error is null."
                },
            )

            resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
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
     * Attempt to destroy the current Verve fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the partner ad to report its destroy result.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        fun destroyAd(destroy: () -> Unit): Result<PartnerAd> {
            destroy()
            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            return Result.success(partnerAd)
        }

        return when (partnerAd.request.format) {
            PartnerAdFormats.INTERSTITIAL -> {
                loadIdToHyBidInterstitialAds.remove(partnerAd.request.identifier)?.let {
                    destroyAd(it::destroy)
                } ?: run {
                    PartnerLogController.log(INVALIDATE_SUCCEEDED, "Ad is already null.")
                    Result.success(partnerAd)
                }
            }
            PartnerAdFormats.REWARDED -> {
                loadIdToHyBidRewardedAds.remove(partnerAd.request.identifier)?.let {
                    destroyAd(it::destroy)
                } ?: run {
                    PartnerLogController.log(INVALIDATE_SUCCEEDED, "Ad is already null.")
                    Result.success(partnerAd)
                }
            }
            else -> {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not HyBidInterstitialAd or HyBidRewardedAd.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }

    /**
     * Convert a given Verve error into a [ChartboostMediationError].
     *
     * @param error The Verve error code to convert.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: Throwable?) =
        when ((error as? HyBidError)?.errorCode) {
            HyBidErrorCode.EXPIRED_AD -> ChartboostMediationError.ShowError.AdExpired
            HyBidErrorCode.INTERNAL_ERROR -> ChartboostMediationError.OtherError.InternalError
            HyBidErrorCode.INVALID_AD, HyBidErrorCode.INVALID_URL -> ChartboostMediationError.LoadError.InvalidAdRequest
            HyBidErrorCode.INVALID_ZONE_ID -> ChartboostMediationError.LoadError.InvalidPartnerPlacement
            HyBidErrorCode.NOT_INITIALISED -> ChartboostMediationError.LoadError.PartnerNotInitialized
            HyBidErrorCode.UNKNOWN_ERROR -> ChartboostMediationError.OtherError.Unknown
            HyBidErrorCode.UNSUPPORTED_ASSET -> ChartboostMediationError.LoadError.UnsupportedAdFormat

            HyBidErrorCode.ERROR_RENDERING_BANNER,
            HyBidErrorCode.ERROR_RENDERING_INTERSTITIAL,
            HyBidErrorCode.ERROR_RENDERING_REWARDED,
            -> ChartboostMediationError.ShowError.MediaBroken

            HyBidErrorCode.VAST_PLAYER_ERROR,
            HyBidErrorCode.MRAID_PLAYER_ERROR,
            -> ChartboostMediationError.ShowError.VideoPlayerError

            HyBidErrorCode.AUCTION_NO_AD,
            HyBidErrorCode.NO_FILL,
            -> ChartboostMediationError.LoadError.NoFill
            else -> ChartboostMediationError.OtherError.PartnerError
        }
}
