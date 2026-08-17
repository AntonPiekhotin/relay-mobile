package com.relay.call

object IosRtc {
    var factory: RtcClientFactory? = null
}

class BridgedRtcClientFactory : RtcClientFactory {
    override fun create(): RtcClient =
        IosRtc.factory?.create() ?: UnavailableRtcClient(RTC_UNAVAILABLE)
}
