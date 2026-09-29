package com.example.spoof

import android.os.Handler
import android.os.Looper

/** Tiny in-process status bus between the service and the UI. */
object Status {
    var running = false
        private set
    var message = ""
        private set

    /** Last position pushed to the test providers, or null when not mocking. */
    var position: Pair<Double, Double>? = null
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(running: Boolean, message: String, position: Pair<Double, Double>? = null) {
        main.post {
            this.running = running
            this.message = message
            this.position = position
            listeners.toList().forEach { it() }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
