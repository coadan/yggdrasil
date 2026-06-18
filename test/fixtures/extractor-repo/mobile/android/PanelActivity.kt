package com.example.panels

import android.app.Activity
import com.example.panels.data.PanelRepository

class PanelActivity : Activity() {
    val title = "Panels"

    fun loadPanel(id: String) {
        PanelRepository.load(id)
    }

    companion object {
        const val routePrefix = "/panels"
    }
}

object PanelRoutes {
    fun route(id: String) = "/panels/$id"
}

enum class PanelMode {
    Detail
}

annotation class PanelBinding
