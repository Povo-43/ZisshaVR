package dev.zissha.vr.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Thermal / power management (spec 11).
 *
 * Watches PowerManager thermal status and translates it into a degradation level 0..4:
 *   0 none      -> full quality
 *   1 throttling light    -> stop Face/Hand inference, reduce tracking stride
 *   2 moderate  -> lower inference rate further
 *   3 severe    -> lower inference resolution stride, reduce render quality
 *   4 critical  -> minimum render FPS, minimum tracking
 *
 * Safety features are never disabled by power saving (spec 11 last rule).
 */
class PerformanceGovernor(context: Context) {

    interface Listener {
        fun onGovernorLevelChanged(level: Int)
    }

    var level: Int = 0
        private set

    var listener: Listener? = null

    val thermalStatusText: String
        get() = when (thermalStatus) {
            THERMAL_STATUS_NONE -> "NONE"
            THERMAL_STATUS_LIGHT -> "LIGHT"
            THERMAL_STATUS_MODERATE -> "MODERATE"
            THERMAL_STATUS_SEVERE -> "SEVERE"
            THERMAL_STATUS_CRITICAL -> "CRITICAL"
            THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }

    @Volatile var thermalStatus: Int = THERMAL_STATUS_NONE
        private set

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var registered = false

    private val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = status
            applyThermal(status)
        }
    } else {
        null
    }

    fun start() {
        if (registered) return
        val listener = thermalListener
        val pm = powerManager
        if (listener == null || pm == null) {
            if (listener == null) {
                // Below API 29: thermal status API unavailable; keep level 0.
                Log.w(TAG, "thermal status API unavailable (API < 29); governor disabled")
            }
            return
        }
        try {
            pm.addThermalStatusListener(mainExecutor(), listener)
            thermalStatus = pm.currentThermalStatus
            applyThermal(thermalStatus)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "failed to register thermal listener", e)
        }
    }

    fun stop() {
        if (!registered) return
        val listener = thermalListener ?: return
        val pm = powerManager ?: return
        try {
            pm.removeThermalStatusListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "failed to unregister thermal listener", e)
        }
        registered = false
    }

    private fun applyThermal(status: Int) {
        val newLevel = when (status) {
            THERMAL_STATUS_NONE -> 0
            THERMAL_STATUS_LIGHT -> 1
            THERMAL_STATUS_MODERATE -> 2
            THERMAL_STATUS_SEVERE -> 3
            THERMAL_STATUS_CRITICAL, THERMAL_STATUS_SHUTDOWN -> 4
            else -> 0
        }
        if (newLevel != level) {
            level = newLevel
            Log.i(TAG, "thermal $thermalStatusText -> governor level $level")
            listener?.onGovernorLevelChanged(level)
        }
    }

    private fun mainExecutor(): java.util.concurrent.Executor =
        java.util.concurrent.Executor { r ->
            android.os.Handler(android.os.Looper.getMainLooper()).post(r)
        }

    companion object {
        private const val TAG = "PerfGovernor"

        // PowerManager thermal status constants (API 29+)
        private const val THERMAL_STATUS_NONE = 0
        private const val THERMAL_STATUS_LIGHT = 1
        private const val THERMAL_STATUS_MODERATE = 2
        private const val THERMAL_STATUS_SEVERE = 3
        private const val THERMAL_STATUS_CRITICAL = 4
        private const val THERMAL_STATUS_SHUTDOWN = 5
    }
}
