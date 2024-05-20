package com.chartboost.mediation.verveadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import net.pubnative.lite.sdk.HyBid
import net.pubnative.lite.sdk.utils.Logger

object VerveAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "verve"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Verve"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion: String = HyBid.getHyBidVersion()

    /**
     * The partner adapter version.
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
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_VERVE_ADAPTER_VERSION

    /**
     * Test mode option that can be enabled to test Verve SDK integrations.
     */
    var testModeEnabled = HyBid.isTestMode()
        set(value) {
            HyBid.setTestMode(value)
            field = value
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "Verve SDK test mode is ${if (value) "enabled" else "disabled"}.",
            )
        }

    /**
     * Log level option that can be set to alter the output verbosity.
     */
    var logLevel = Logger.Level.info
        set(value) {
            HyBid.setLogLevel(value)
            field = value
            PartnerLogController.log(PartnerLogController.PartnerAdapterEvents.CUSTOM, "Verve SDK log level set to $value.")
        }
}
