package com.hyundai.rentalhelper.service

/**
 * Decides when a Pleos STT session is really capturing audio. Rule (from the SDK bytecode and service logs):
 * onReady = service event "ReadyToStartRecognition", sent before the service creates its AudioRecord, so it is
 * NOT capture; audio evidence (RMS frames, a non-empty partial, EPD speech start) counts only after onReady.
 * Pure Kotlin so the rule is unit-tested (SttCaptureGateTest).
 */
class SttCaptureGate {
    var readySeen = false
        private set
    var captureConfirmed = false
        private set
    /** Audio callbacks received before onReady (stale or pre-recorder events); ignored. */
    var ignoredBeforeReady = 0
        private set

    fun onReady() {
        readySeen = true
    }

    /** Returns true exactly once: the first audio evidence after onReady. */
    fun onAudioEvidence(): Boolean {
        if (captureConfirmed) return false
        if (!readySeen) {
            ignoredBeforeReady++
            return false
        }
        captureConfirmed = true
        return true
    }
}
