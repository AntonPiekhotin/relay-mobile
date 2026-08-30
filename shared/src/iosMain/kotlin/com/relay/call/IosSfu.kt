package com.relay.call

object IosSfu {
    var factory: SfuClientFactory? = null
}

class BridgedSfuClientFactory : SfuClientFactory {
    override fun create(): SfuClient =
        IosSfu.factory?.create() ?: UnavailableSfuClient(SFU_UNAVAILABLE)
}
