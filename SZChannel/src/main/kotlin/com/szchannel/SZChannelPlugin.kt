package com.szchannel

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SZChannelPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SZChannel())
    }
}
