package com.arogyax.core

/**
 * The in-app assistant, advanced spec section 15.
 *
 * ## Why this is a lookup table and not a language model
 *
 * Non-negotiable 7: *no model-generated text, ever.* Section 15.4 asks for the
 * same thing from the other direction — never prescribe, never diagnose, never
 * override a clinician, never invent missing patient information. A generative
 * model cannot be made to satisfy "never invent"; a fixed answer set satisfies
 * it by construction.
 *
 * So this matches an utterance to one of a closed set of [Intent]s and fills a
 * reviewed template with numbers taken from the caller's own records. Every
 * string a worker can see is in this file and can be read in one sitting. An
 * unmatched question returns [Intent.UNKNOWN] and says so, which is the honest
 * answer and also the safe one.
 *
 * The tradeoff is real and worth stating: this understands far less than a
 * language model would. It is the correct tradeoff for a device that triages
 * strangers on a doorstep with no clinician present.
 */
enum class Intent {
    WHY_PRIORITISED,
    POOR_QUALITY_HELP,
    TODAY_COUNT,
    OVERDUE_REFERRALS,
    PREVIOUS_VISIT,
    WHAT_IS_TIER,
    OFFLINE_HELP,
    CLINICAL_QUESTION,
    UNKNOWN,
}

/** What the assistant is allowed to read. Nothing else is in scope. */
data class AssistantContext(
    val screenedToday: Int = 0,
    val referralsPending: Int = 0,
    val referralsOverdue: Int = 0,
    val lastTier: Tier? = null,
    val lastReasons: List<Reason> = emptyList(),
    val priorVisits: Int = 0,
    val lastQualityPercent: Int? = null,
)

data class AssistantAnswer(
    val intent: Intent,
    val text: String,
    /** True when the question needs a clinician, not this app. Section 15.4. */
    val deferredToClinician: Boolean = false,
)

object Assistant {

    /**
     * Questions that must never be answered here, however they are phrased.
     *
     * Checked before intent matching, so a treatment question that happens to
     * contain "priority" cannot be routed to an explanation.
     */
    private val CLINICAL_TRIGGERS = listOf(
        "medicine", "medication", "drug", "dose", "dosage", "tablet", "prescribe",
        "prescription", "treatment", "treat", "cure", "warfarin", "aspirin",
        "blood thinner", "anticoagulant", "surgery", "diagnose", "diagnosis",
        "does he have", "does she have", "do i have", "is it serious", "will i die",
    )

    private val SUGGESTIONS = listOf(
        "Why was this patient prioritised?",
        "What should I do when signal quality is poor?",
        "How many screenings today?",
        "Which referrals are overdue?",
        "What does this colour mean?",
        "Does sync need internet?",
    )

    fun suggestions(): List<String> = SUGGESTIONS

    fun classify(qRaw: String): Intent {
        val q = qRaw.lowercase().trim()
        if (q.isBlank()) return Intent.UNKNOWN
        if (CLINICAL_TRIGGERS.any { q.contains(it) }) return Intent.CLINICAL_QUESTION

        return when {
            q.has("why") && q.has("priorit", "flag", "referr", "high", "red", "orange") ->
                Intent.WHY_PRIORITISED
            q.has("quality", "poor signal", "bad signal", "unclear", "retake", "repeat") ->
                Intent.POOR_QUALITY_HELP
            q.has("how many", "today", "count", "screened") -> Intent.TODAY_COUNT
            q.has("overdue", "pending", "outstanding", "follow up", "follow-up") ->
                Intent.OVERDUE_REFERRALS
            q.has("previous", "last visit", "earlier", "history", "before") -> Intent.PREVIOUS_VISIT
            q.has("colour", "color", "mean", "tier", "what is red", "what is green") ->
                Intent.WHAT_IS_TIER
            q.has("offline", "internet", "network", "sync", "connection") -> Intent.OFFLINE_HELP
            else -> Intent.UNKNOWN
        }
    }

    private fun String.has(vararg needles: String) = needles.any { this.contains(it) }

    fun answer(question: String, ctx: AssistantContext): AssistantAnswer {
        val intent = classify(question)
        val text = when (intent) {
            Intent.CLINICAL_QUESTION -> return AssistantAnswer(
                intent,
                "That is a question for the doctor at the PHC, not for this app. " +
                    "This device only decides how soon someone should be seen — it does " +
                    "not identify conditions and never advises on treatment.",
                deferredToClinician = true,
            )

            Intent.WHY_PRIORITISED -> {
                val tier = ctx.lastTier
                if (tier == null) {
                    "No screening has been completed yet, so there is nothing to explain."
                } else {
                    val lines = ctx.lastReasons.joinToString("\n") { "  • ${readable(it)}" }
                    buildString {
                        append("The last screening was marked ${labelOf(tier)}.\n\n")
                        if (lines.isNotBlank()) append("Reasons recorded:\n$lines\n\n")
                        append("This is a screening priority, not a diagnosis.")
                    }
                }
            }

            Intent.POOR_QUALITY_HELP -> buildString {
                append("A recording is refused rather than scored when it is unclear — ")
                append("that is deliberate, because a noisy trace can look like an irregular one.\n\n")
                append("In order, check:\n")
                append("  • Electrodes flat on clean, dry skin\n")
                append("  • Patient sitting still, arms resting\n")
                append("  • Away from wiring; charger unplugged\n")
                append("  • Hold contact until the countdown ends\n\n")
                ctx.lastQualityPercent?.let { append("Last recording scored $it%.\n") }
                append("After ${AdaptiveRepeat.MAX_ATTEMPTS} attempts, stop and refer the patient for review.")
            }

            Intent.TODAY_COUNT ->
                "${ctx.screenedToday} screening(s) completed on this device today. " +
                    "${ctx.referralsPending} referral(s) are waiting to be sent."

            Intent.OVERDUE_REFERRALS -> if (ctx.referralsOverdue == 0) {
                "No referrals are overdue. ${ctx.referralsPending} are pending."
            } else {
                "${ctx.referralsOverdue} referral(s) are overdue and should be followed up " +
                    "on the next household visit."
            }

            Intent.PREVIOUS_VISIT -> if (ctx.priorVisits == 0) {
                "There is no earlier screening recorded on this device for this patient."
            } else {
                "This patient has ${ctx.priorVisits} earlier screening(s) on this device. " +
                    "Open the patient timeline to see each one."
            }

            Intent.WHAT_IS_TIER -> buildString {
                append("The colour is how soon the patient should be seen:\n\n")
                for (t in listOf(Tier.RED, Tier.ORANGE, Tier.YELLOW, Tier.GREEN, Tier.RETAKE)) {
                    append("  • ${labelOf(t)} — ${timeframeOf(t)}\n")
                }
                append("\nNone of these names a condition. Only a doctor decides what it is.")
            }

            Intent.OFFLINE_HELP ->
                "No. Screening works fully offline — the recording, the analysis and the " +
                    "result all happen on this phone. Records sync later, on their own, " +
                    "when a network appears. A result never waits for a connection."

            Intent.UNKNOWN ->
                "I can only answer a fixed set of questions about this app and the records " +
                    "on it. Try one of the suggestions, or ask the PHC staff."
        }
        return AssistantAnswer(intent, text)
    }

    /** Worker-facing tier wording. Kept here so the assistant cannot invent its own. */
    fun labelOf(t: Tier) = when (t) {
        Tier.RED -> "PRIORITY REFERRAL"
        Tier.ORANGE -> "REFERRAL"
        Tier.YELLOW -> "REFERRAL"
        Tier.GREEN -> "ROUTINE"
        Tier.RETAKE -> "REPEAT CAPTURE"
    }

    fun timeframeOf(t: Tier) = when (t) {
        Tier.RED -> "see today, within 4 hours"
        Tier.ORANGE -> "see within 24 hours"
        Tier.YELLOW -> "see within 48 hours"
        Tier.GREEN -> "no rhythm concern today"
        Tier.RETAKE -> "not a result; record again"
    }

    /**
     * Renders a [Reason] key as English.
     *
     * A key with no entry here returns the key itself rather than a guess —
     * visibly wrong in review, rather than plausibly wrong in the field.
     */
    fun readable(r: Reason): String {
        val base = TEXT[r.key] ?: return r.key
        return r.values.entries.fold(base) { acc, (k, v) -> acc.replace("{$k}", v) }
    }

    private val TEXT: Map<String, String> = mapOf(
        "risk_retake_not_scored" to "The recording was not clear enough to score",
        "risk_persistent_pattern" to "Every earlier screening was flagged",
        "risk_intermittent_pattern" to "Flagged on some earlier visits but not others",
        "risk_repeated_suspicious" to "Flagged on several recent visits in a row",
        "risk_trajectory_increasing" to "Flagged more often in recent visits than earlier ones",
        "risk_referral_lapsed" to "An earlier referral was never followed up",
        "risk_referral_open" to "An earlier referral is still open",
        "risk_previously_confirmed" to "A doctor has confirmed a finding before",
        "risk_no_history_factors" to "No earlier visits changed this result",
        "risk_baseline_hr_above" to "Heart rate {current} bpm is above this patient's usual {baseline} bpm",
        "risk_baseline_hr_below" to "Heart rate {current} bpm is below this patient's usual {baseline} bpm",
    )
}
