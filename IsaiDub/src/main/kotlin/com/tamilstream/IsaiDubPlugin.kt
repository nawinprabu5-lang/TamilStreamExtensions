package com.tamilstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IsaiDubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IsaiDubProvider())
    }
}
