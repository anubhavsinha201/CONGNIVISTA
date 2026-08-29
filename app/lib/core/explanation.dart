import '../data/patient_history.dart';
import 'policy.dart';

/// One reason a tier came out the way it did.
///
/// A key plus its numbers, never a sentence. The UI looks the key up in the
/// static Tamil/English string table and substitutes the values. That keeps the
/// worker-facing wording reviewable by a clinician and keeps translation in one
/// place — and it is what makes this explainability rather than generation.
class Reason {
  /// Stable identifier for the string table. Never shown to anyone.
  final String key;

  /// Values to substitute, already rounded for display.
  final Map<String, String> values;

  const Reason(this.key, [this.values = const {}]);

  @override
  String toString() => values.isEmpty ? key : '$key ${values.toString()}';
}

/// Turns a decision into the reasons behind it.
///
/// ## Why this is templated and not generated
///
/// The feature request was "explainable AI → NLP". Generated prose is the one
/// thing this product cannot ship: CLAUDE.md non-negotiable 7 forbids
/// model-generated worker-facing text, and non-negotiable 1 forbids anything
/// that reads as a diagnosis. A language model asked to explain an AF screen
/// will, sooner or later, write the word.
///
/// It is also unnecessary. Every input to the decision is already a named
/// number — `decidedBy`, `ppgCorroboration`, the irregularity score, the rate,
/// the pulse deficit. The decision is a short chain of arithmetic over those,
/// so it can be explained exactly rather than approximately. A generated
/// sentence would be a less faithful account of the same logic, with a new way
/// to be wrong.
///
/// What a clinician gets here is the actual reasoning. What an LLM would give
/// is a plausible-sounding paraphrase of it.
class Explainer {
  const Explainer._();

  /// Reasons for a single screening, most important first.
  static List<Reason> forDecision(TierDecision d, TierInputs i) {
    final out = <Reason>[];

    if (d.tier == Tier.retake) {
      out.add(Reason(switch (d.retakeReason) {
        RetakeReason.electrodeDetached => 'why.retake.electrode',
        RetakeReason.patientMoved => 'why.retake.motion',
        RetakeReason.droppedData => 'why.retake.connection',
        RetakeReason.poorSignalQuality => 'why.retake.quality',
        RetakeReason.tooFewBeats => 'why.retake.tooShort',
        RetakeReason.beatDetectionUnreliable => 'why.retake.beatDetection',
        null => 'why.retake.generic',
      }, {
        'sqi': _pct(i.sqiScore),
      }));
      return out;
    }

    // Rhythm. The primary finding, so it leads.
    final irregular = i.rrIrregularityScore >= Policy.kRrIrregularityGate ||
        (i.cnnScore != null &&
            Policy.kCnnThresholdInt8 != null &&
            i.cnnScore! >= Policy.kCnnThresholdInt8!);

    out.add(Reason(
      irregular ? 'why.rhythm.irregular' : 'why.rhythm.regular',
      {'score': _pct(i.rrIrregularityScore)},
    ));

    // Which detector, so a clinician can weigh the evidence rather than
    // taking the tier on faith.
    out.add(Reason(switch (d.decidedBy) {
      DecidedBy.rules => 'why.source.rules',
      DecidedBy.cnn => 'why.source.model',
      DecidedBy.rulesAndCnn => 'why.source.both',
      DecidedBy.gate => 'why.source.gate',
    }));

    // Rate decides urgency, not presence — say which it did.
    if (i.meanHr < Policy.kHrLow) {
      out.add(Reason('why.rate.low', {'hr': i.meanHr.round().toString()}));
    } else if (i.meanHr > Policy.kHrHigh) {
      out.add(Reason('why.rate.high', {'hr': i.meanHr.round().toString()}));
    } else {
      out.add(Reason('why.rate.normal', {'hr': i.meanHr.round().toString()}));
    }

    // The PPG's contribution, when it had one. This is the field that makes an
    // AMBER-to-RED escalation auditable rather than mysterious.
    switch (d.ppg) {
      case PpgCorroboration.pulseDeficit:
        out.add(Reason('why.ppg.pulseDeficit', {
          'deficit': (i.pulseDeficitBpm ?? 0).round().toString(),
        }));
      case PpgCorroboration.nonPerfusingBeats:
        out.add(Reason('why.ppg.nonPerfusing', {
          'perfused': _pct(i.perfusedBeatFraction ?? 0),
        }));
      case PpgCorroboration.agreed:
        out.add(const Reason('why.ppg.agreed'));
      case PpgCorroboration.unusable:
        out.add(const Reason('why.ppg.unusable'));
      case PpgCorroboration.none:
        break;
    }

    return out;
  }

  /// Reasons a patient is due for a repeat visit. Feature 12's "why".
  static List<Reason> forRepeat(PatientHistory h) {
    final out = <Reason>[
      Reason('why.repeat.${h.repeatReasonKey}', {
        'days': h.recommendedRepeatDays.toString(),
        'overdue': (-(h.daysUntilDue ?? 0)).clamp(0, 99999).toString(),
      }),
    ];

    // Only quote a rate once there is enough sampling behind it. Below that it
    // is a coin flip with a decimal point, and a clinician shown "50% of
    // visits" from two screenings will read far more into it than it holds.
    if (h.burdenConfidence != BurdenConfidence.insufficient) {
      out.add(Reason('why.history.flagRate', {
        'flagged': h.flaggedCount.toString(),
        'total': h.scored.length.toString(),
        'days': h.observationDays.toString(),
      }));
    }
    if (h.isIntermittent) {
      out.add(const Reason('why.history.intermittent'));
    }
    if (h.hasLapsedReferral) {
      out.add(const Reason('why.history.referralLapsed'));
    }
    return out;
  }

  static String _pct(double v) => '${(v * 100).round()}%';
}

/// Every key [Explainer] can emit.
///
/// The string table must cover all of these in Tamil and English. Kept here so
/// a test can assert the table is complete — a missing key is a blank line on a
/// health worker's screen at a doorstep, discovered in the field.
const Set<String> kExplanationKeys = {
  'why.retake.electrode',
  'why.retake.motion',
  'why.retake.connection',
  'why.retake.quality',
  'why.retake.tooShort',
  'why.retake.beatDetection',
  'why.retake.generic',
  'why.rhythm.irregular',
  'why.rhythm.regular',
  'why.source.rules',
  'why.source.model',
  'why.source.both',
  'why.source.gate',
  'why.rate.low',
  'why.rate.high',
  'why.rate.normal',
  'why.ppg.pulseDeficit',
  'why.ppg.nonPerfusing',
  'why.ppg.agreed',
  'why.ppg.unusable',
  'why.repeat.never_screened',
  'why.repeat.under_clinician_care',
  'why.repeat.referral_open',
  'why.repeat.varies_between_visits',
  'why.repeat.previous_urgent_referral',
  'why.repeat.previous_referral',
  'why.repeat.last_capture_unusable',
  'why.repeat.routine',
  'why.history.flagRate',
  'why.history.intermittent',
  'why.history.referralLapsed',
};
