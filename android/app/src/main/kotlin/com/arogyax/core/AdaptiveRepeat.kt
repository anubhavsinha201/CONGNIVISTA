package com.arogyax.core

/**
 * Adaptive repeat measurement, advanced spec section 13.
 *
 * Replaces "ECG failed — retry" with the specific thing the worker should
 * change, and — the part that matters more — stops the loop. A worker who is
 * asked to retake five times in a doorway will either give up on the patient or
 * start recording rubbish to make the app happy. Neither is a screening result.
 *
 * Section 13.4's policy: attempt 1 repeats, attempt 2 repeats with a stronger
 * instruction, attempt 3 stops asking and hands the patient to a clinician or a
 * later visit. [MAX_ATTEMPTS] is that limit, and the spec is explicit that it is
 * provisional until field-tested — it is not a measured value.
 */
enum class RepeatAction {
    /** Capture again; the instruction says what to change. */
    REPEAT,

    /** Capture again, but the worker is told plainly this is the last attempt. */
    REPEAT_FINAL,

    /**
     * Stop. Do not capture again this visit. The patient is flagged for a
     * clinician or a later screening rather than being left with no outcome.
     */
    ESCALATE,
}

data class RepeatGuidance(
    val action: RepeatAction,
    /** The single thing to change. One sentence, imperative, no jargon. */
    val instruction: String,
    /** Stable key for the Tamil string table — never a generated sentence. */
    val instructionKey: String,
    val attempt: Int,
    val attemptsRemaining: Int,
) {
    val shouldCaptureAgain: Boolean get() = action != RepeatAction.ESCALATE
}

object AdaptiveRepeat {

    /**
     * Attempts allowed per patient per visit before escalating.
     *
     * PROVISIONAL — advanced spec section 13.4 says the final policy is to be
     * validated during field testing. Nothing has been field-tested here.
     */
    const val MAX_ATTEMPTS = 3

    /**
     * @param reason why the last capture was refused
     * @param attempt 1-based index of the capture just refused
     * @param quality the quality panel for that capture, when one exists. A
     *   RETAKE from a BLE gap or a detached lead has no meaningful quality
     *   breakdown, so this is null on those paths.
     */
    fun guide(
        reason: RetakeReason,
        attempt: Int,
        quality: EcgQualityReport? = null,
    ): RepeatGuidance {
        require(attempt >= 1) { "attempt is 1-based, got $attempt" }

        val remaining = (MAX_ATTEMPTS - attempt).coerceAtLeast(0)
        val action = when {
            attempt >= MAX_ATTEMPTS -> RepeatAction.ESCALATE
            attempt == MAX_ATTEMPTS - 1 -> RepeatAction.REPEAT_FINAL
            else -> RepeatAction.REPEAT
        }

        if (action == RepeatAction.ESCALATE) {
            return RepeatGuidance(
                action = action,
                instruction = "Could not get a clear recording after $MAX_ATTEMPTS attempts. " +
                    "Record this visit and ask the PHC to review the patient.",
                instructionKey = "repeat_escalate",
                attempt = attempt,
                attemptsRemaining = 0,
            )
        }

        // Section 13.2 walks motion -> contact -> noise in that order. The gate
        // that actually refused the window is a stronger signal than anything
        // inferred afterwards, so it is consulted first; the quality breakdown
        // only refines the generic "poor signal" case.
        val (key, text) = when (reason) {
            RetakeReason.PATIENT_MOVED ->
                "repeat_motion" to "Ask the patient to sit still and rest their arms, then record again."

            RetakeReason.ELECTRODE_DETACHED ->
                "repeat_contact" to "Reattach the electrodes to clean, dry skin and record again."

            RetakeReason.DROPPED_DATA ->
                "repeat_connection" to "Keep the phone within arm's reach of the device, then record again."

            RetakeReason.TOO_FEW_BEATS ->
                "repeat_too_short" to "Hold contact until the countdown finishes, then record again."

            RetakeReason.BEAT_DETECTION_UNRELIABLE ->
                "repeat_beat_detection" to
                    "The beat readings did not agree. Reposition the electrodes and record again."

            RetakeReason.POOR_SIGNAL_QUALITY -> {
                // Name the worst-scoring factor rather than saying "poor
                // signal", which tells the worker nothing they can act on.
                val worst = quality?.worst
                when (worst?.label) {
                    "Electrode contact" ->
                        "repeat_contact" to "Press the electrodes firmly onto clean, dry skin and record again."
                    "Signal amplitude" ->
                        "repeat_amplitude" to "The signal is clipping. Reposition the electrodes and record again."
                    "Electrical noise" ->
                        "repeat_noise" to "Move away from wiring, unplug the charger, and record again."
                    "Steadiness" ->
                        "repeat_motion" to "Ask the patient to sit still and breathe normally, then record again."
                    else ->
                        "repeat_generic" to "Reposition the electrodes and record again."
                }
            }
        }

        val stronger = if (action == RepeatAction.REPEAT_FINAL) {
            "$text This is the last attempt for this visit."
        } else {
            text
        }

        return RepeatGuidance(
            action = action,
            instruction = stronger,
            instructionKey = key,
            attempt = attempt,
            attemptsRemaining = remaining,
        )
    }
}
