package com.example.spoof

import android.os.Handler
import android.os.Looper

/** Tiny in-process status bus between the service and the UI. */
object Status {
    var running = false
        private set
    var message = ""
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(running: Boolean, message: String) {
        main.post {
            this.running = running
            this.message = message
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
