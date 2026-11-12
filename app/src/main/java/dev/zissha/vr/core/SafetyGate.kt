package dev.zissha.vr.core

import dev.zissha.protocol.OutputMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fail-safe output gate (spec 7).
 *
 * LIVE     : normal composited output
 * FREEZE   : hold the last verified-safe composited frame; camera updates are not emitted
 * PRIVACY  : user-provided still image replaces all outputs
 *
 * Rules enforced here:
 * - Frames that cannot be verified as safe are never emitted.
 * - Recovery from FREEZE requires the user; 0.5 s of continuous healthy tracking only *arms*
 *   the recovery (spec 7.3).
 * - PRIVACY can always be engaged immediately and has the highest priority.
 */
class SafetyGate {

    private val state = MutableStateFlow(OutputMode.LIVE)
    val mode: StateFlow<OutputMode> = state

    /** True when tracking recovered long enough that the user may switch back to LIVE. */
    private val recoveryArmed = MutableStateFlow(false)
    val canRecover: StateFlow<Boolean> = recoveryArmed

    /** True while in FREEZE/PRIVACY because of an automatic trigger (vs manual user action). */
    private val lastTrigger = MutableStateFlow<String?>(null)
    val trigger: StateFlow<String?> = lastTrigger

    private val healthySince = AtomicLong(0)
    private val lock = Any()

    companion object {
        private const val RECOVERY_MS = 500L
    }

    /** Manual control from UI / BLE remote. Always synchronous (spec 7.4). */
    fun requestMode(mode: OutputMode) {
        synchronized(lock) {
            when (mode) {
                OutputMode.PRIVACY -> {
                    state.value = OutputMode.PRIVACY
                    lastTrigger.value = null
                    recoveryArmed.value = false
                    healthySince.set(0)
                }
                OutputMode.FREEZE -> {
                    state.value = OutputMode.FREEZE
                    lastTrigger.value = null
                    recoveryArmed.value = false
                    healthySince.set(0)
                }
                OutputMode.LIVE -> {
                    // returning to LIVE is only allowed when recovery is armed (or gate is LIVE already)
                    if (state.value == OutputMode.LIVE || recoveryArmed.get()) {
                        state.value = OutputMode.LIVE
                        lastTrigger.value = null
                        recoveryArmed.value = false
                        healthySince.set(0)
                    }
                }
            }
        }
    }

    /**
     * Feed per-frame tracking health. Called for every rendered frame.
     * @param safeFrameExists whether a verified safe composited frame is available to freeze onto
     */
    fun onFrame(tsMs: Long, healthy: Boolean, reason: String?, safeFrameExists: Boolean) {
        val current = state.value
        when (current) {
            OutputMode.LIVE -> {
                if (!healthy) {
                    synchronized(lock) {
                        // no verified-safe frame yet -> must fall back to PRIVACY image
                        state.value = if (safeFrameExists) OutputMode.FREEZE else OutputMode.PRIVACY
                        lastTrigger.value = reason ?: "tracking_anomaly"
                        recoveryArmed.value = false
                        healthySince.set(0)
                    }
                }
            }
            OutputMode.FREEZE -> {
                if (healthy) {
                    val since = healthySince.updateAndGet { prev ->
                        if (prev == 0L) tsMs else prev
                    }
                    if (tsMs - since >= RECOVERY_MS) recoveryArmed.value = true
                } else {
                    healthySince.set(0)
                    recoveryArmed.value = false
                }
            }
            OutputMode.PRIVACY -> {
                // stays until the user switches back
            }
        }
    }

    /** Camera / render watchdog events. */
    fun onCameraStall() = triggerAuto("camera_stall")
    fun onInferenceTimeout() = triggerAuto("inference_timeout")
    fun onSegmentationAnomaly() = triggerAuto("segmentation_anomaly")
    fun onInternalError(what: String) = triggerAuto("internal_error:$what")

    private fun triggerAuto(reason: String) {
        synchronized(lock) {
            if (state.value == OutputMode.LIVE) {
                state.value = if (safeFrameAvailable) OutputMode.FREEZE else OutputMode.PRIVACY
                lastTrigger.value = reason
                recoveryArmed.value = false
                healthySince.set(0)
            }
        }
    }

    /** Set by the compositor: at least one verified-safe composite frame exists. */
    @Volatile var safeFrameAvailable: Boolean = false
        private set

    fun notifySafeFrameStored() {
        safeFrameAvailable = true
    }
}
