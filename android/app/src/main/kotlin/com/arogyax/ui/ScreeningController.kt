package com.arogyax.ui

import com.arogyax.core.AdaptiveRepeat
import com.arogyax.core.EcgQuality
import com.arogyax.core.EcgQualityReport
import com.arogyax.core.Explainer
import com.arogyax.core.Policy
import com.arogyax.core.Reason
import com.arogyax.core.RepeatGuidance
import com.arogyax.core.RiskAssessment
import com.arogyax.core.RiskEngine
import com.arogyax.core.Tier
import com.arogyax.core.TierDecision
import com.arogyax.core.TierInputs
import com.arogyax.data.PatientHistory
import com.arogyax.data.ReferralState
import com.arogyax.data.SignalSource
import com.arogyax.data.TimelineEntry
import com.arogyax.signal.PanTompkins
import com.arogyax.signal.RrAnalyser
import com.arogyax.signal.SqiAnalyser
import java.time.OffsetDateTime
import java.util.UUID

enum class Screen { HOME, PATIENT, CAPTURE, RESULT, TIMELINE, REFERRALS, DISTRICT, ASSISTANT }

/** Demographics the record schema needs. `ageBand` and `villageCode` are required in v4. */
data class PatientEntry(
    var pseudoId: String = "",
    var ageBand: String = "",
    var villageCode: String = "",
    var sex: String? = null,
    var systolicBp: String = "",
    var diastolicBp: String = "",
    var glucose: String = "",
) {
    val complete: Boolean get() = pseudoId.isNotBlank() && ageBand.isNotBlank() && villageCode.isNotBlank()
}

/** Everything one completed screening produced. */
class ScreeningResult(
    val patient: PatientEntry,
    val decision: TierDecision,
    val inputs: TierInputs,
    val quality: EcgQualityReport,
    val reasons: List<Reason>,
    val risk: RiskAssessment,
    val repeat: RepeatGuidance?,
    val waveform: DoubleArray,
    val peaks: IntArray,
    val capturedAt: OffsetDateTime,
) {
    val referrable: Boolean get() = decision.tier in setOf(Tier.RED, Tier.ORANGE, Tier.YELLOW)
    var referralState: ReferralState = ReferralState.NONE
}

/**
 * Everything the worker-facing flow does, with no Android or UI type in sight so
 * it stays unit-testable on a plain JVM.
 *
 * It computes nothing clinical of its own. Every number it surfaces comes from
 * the already-pinned chain — [SqiAnalyser], [PanTompkins], [RrAnalyser],
 * [Policy], [RiskEngine], [Explainer]. If a value reaches the screen that none
 * of those produced, that is a bug, not a display choice.
 */
class ScreeningController(
    private val source: SignalSource,
    private val fs: Double = 250.0,
) {
    var screen: Screen = Screen.HOME
    var patient: PatientEntry = PatientEntry()
    var current: ScreeningResult? = null

    /**
     * Capture attempts for the patient in hand. A refused window does not reset
     * it — that is precisely what makes [AdaptiveRepeat] terminate instead of
     * asking a worker to retake forever.
     */
    var attempt: Int = 1
        private set

    /** Completed screenings this session, newest first. */
    val session = mutableListOf<ScreeningResult>()

    /**
     * Called after every completed screening, so the caller can persist it.
     * A no-op by default, which keeps this class free of storage concerns and
     * unit-testable without a filesystem.
     */
    var onRecorded: (ScreeningResult) -> Unit = {}

    /** Non-negotiable 4: nothing in the screening path reads this. */
    var online: Boolean = false
    var voiceEnabled: Boolean = true

    fun beginPatient() {
        patient = PatientEntry(pseudoId = "P-" + UUID.randomUUID().toString().take(6).uppercase())
        attempt = 1
        current = null
        screen = Screen.PATIENT
    }

    fun historyFor(pseudoId: String): PatientHistory = PatientHistory(
        patientPseudoId = pseudoId,
        timeline = session.filter { it.patient.pseudoId == pseudoId }.mapIndexed { i, r ->
            TimelineEntry(
                recordId = "s$i",
                capturedAt = r.capturedAt,
                tier = r.decision.tier.name,
                meanHr = r.inputs.meanHr,
                rrIrregularityScore = r.inputs.rrIrregularityScore,
                referralState = if (r.referrable) r.referralState else null,
            )
        },
    )

    /**
     * Runs the real chain over a captured window and files the result.
     *
     * @param dataGap a BLE sequence gap occurred during this capture. Must reach
     *   [Policy] rather than being dropped here: a missing frame fabricates a
     *   short RR interval that looks exactly like AF, so the window is refused
     *   (non-negotiable 3).
     * @param leadOff the AD8232's own LO+/LO- pins reported an electrode off the
     *   skin. Hardware truth, never inferred from a flat trace.
     */
    fun analyse(
        raw: DoubleArray,
        dataGap: Boolean = false,
        leadOff: Boolean = false,
    ): ScreeningResult {
        val sqi = SqiAnalyser(fs).analyse(raw)
        val quality = EcgQuality.of(sqi)

        val detected = PanTompkins(fs).detect(raw)
        val rr = RrAnalyser().analyse(detected.rrIntervalsMs(fs))

        val history = historyFor(patient.pseudoId)
        val inputs = TierInputs(
            sqiScore = sqi.score,
            motionRejected = false,
            leadOffDetected = leadOff,
            dataGapDetected = dataGap,
            rrIntervalCount = rr.count,
            meanHr = rr.meanHr,
            rrIrregularityScore = rr.irregularityScore,
            sqiFailureHint = sqi.failureReason,
            historyIntermittent = history.isIntermittent,
            historyPersistent = history.isPersistent,
        )

        val decision = Policy.decide(inputs)
        val result = ScreeningResult(
            patient = patient.copy(),
            decision = decision,
            inputs = inputs,
            quality = quality,
            reasons = Explainer.forDecision(decision, inputs),
            risk = RiskEngine.assess(decision, history),
            repeat = decision.retakeReason?.let { AdaptiveRepeat.guide(it, attempt, quality) },
            waveform = raw,
            peaks = detected.peaks.toIntArray(),
            capturedAt = OffsetDateTime.now(),
        )

        session.add(0, result)
        onRecorded(result)
        attempt = if (decision.tier == Tier.RETAKE) attempt + 1 else 1
        current = result
        screen = Screen.RESULT
        return result
    }

    fun capture(): ScreeningResult = analyse(source.captureEcg())

    // ---- Aggregates the dashboard screens read ---------------------------

    fun count(tier: Tier): Int = session.count { it.decision.tier == tier }

    fun scoredCount(): Int = session.count { it.decision.tier != Tier.RETAKE }

    /** Referral queue, worst tier first. Spec section 7.3. */
    fun referralQueue(): List<ScreeningResult> =
        session.filter { it.referrable }.sortedBy { TIER_RANK.indexOf(it.decision.tier) }

    fun pendingReferrals(): Int = referralQueue().count { it.referralState == ReferralState.NONE }

    fun completedReferrals(): Int = referralQueue().count { it.referralState == ReferralState.CLOSED }

    /** Distinct patients seen this session. */
    fun patientCount(): Int = session.map { it.patient.pseudoId }.distinct().size

    /** Screenings grouped by village, for the district view (spec section 10.2). */
    fun byVillage(): Map<String, List<ScreeningResult>> =
        session.filter { it.patient.villageCode.isNotBlank() }.groupBy { it.patient.villageCode }

    private companion object {
        val TIER_RANK = listOf(Tier.RED, Tier.ORANGE, Tier.YELLOW, Tier.GREEN, Tier.RETAKE)
    }
}
