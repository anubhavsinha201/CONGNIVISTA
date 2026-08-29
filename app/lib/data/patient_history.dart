import 'dart:math' as math;

import 'record.dart';

/// One entry in a patient's screening timeline.
class TimelineEntry {
  final DateTime capturedAt;
  final String tier; // RED / ORANGE / YELLOW / GREEN / RETAKE
  final double? meanHr;
  final double? rrIrregularityScore;
  final ReferralState? referralState;
  final ClinicianOutcome? outcome;
  final String recordId;

  const TimelineEntry({
    required this.recordId,
    required this.capturedAt,
    required this.tier,
    this.meanHr,
    this.rrIrregularityScore,
    this.referralState,
    this.outcome,
  });

  /// True when this screening produced a usable answer. RETAKE did not.
  bool get scored => tier != 'RETAKE';

  /// True when the rhythm was flagged. RETAKE is not a negative — it is silence.
  bool get flagged => tier == 'RED' || tier == 'ORANGE' || tier == 'YELLOW';
}

/// How confident we can be in an AF-burden figure, given how much was sampled.
enum BurdenConfidence { insufficient, provisional, usable }

/// A patient's screening history, reduced to the quantities a WHV or a PHC
/// clinician can act on.
///
/// This exists because of docs/PRODUCT.md §3: atrial fibrillation is
/// intermittent, so a single strip misses cases, and the clinical answer — a
/// 14-day patch monitor — is impractical at a doorstep. The scheme already
/// revisits the same households for BP and glucose, so the repeat visits
/// themselves are the sampling strategy. That argument is only true if
/// something actually looks across visits. This is that something.
class PatientHistory {
  final String patientPseudoId;

  /// Newest first.
  final List<TimelineEntry> timeline;

  const PatientHistory({
    required this.patientPseudoId,
    required this.timeline,
  });

  /// Builds a history from this patient's records. Order of input is irrelevant.
  factory PatientHistory.fromRecords(
    String patientPseudoId,
    Iterable<ScreeningRecord> records,
  ) {
    final mine = records
        .where((r) => r.patientPseudoId == patientPseudoId)
        .map((r) => TimelineEntry(
              recordId: r.recordId,
              capturedAt: r.capturedAt,
              tier: r.tier,
              meanHr: r.meanHr,
              rrIrregularityScore: r.rrIrregularityScore,
              referralState: r.referralState,
              outcome: r.clinicianOutcome,
            ))
        .toList()
      ..sort((a, b) => b.capturedAt.compareTo(a.capturedAt));
    return PatientHistory(patientPseudoId: patientPseudoId, timeline: mine);
  }

  // ---- Feature 1: longitudinal risk profile --------------------------------

  int get totalScreenings => timeline.length;

  /// Screenings that produced a tier. RETAKEs are excluded from every rate
  /// below: a refused window is missing data, not a negative result, and
  /// counting it as "not AF" would quietly deflate the burden figure.
  List<TimelineEntry> get scored => timeline.where((e) => e.scored).toList();

  int get retakeCount => timeline.length - scored.length;

  DateTime? get firstScreening =>
      timeline.isEmpty ? null : timeline.last.capturedAt;

  DateTime? get lastScreening =>
      timeline.isEmpty ? null : timeline.first.capturedAt;

  /// Days between the first and most recent screening.
  int get observationDays {
    if (timeline.length < 2) return 0;
    return lastScreening!.difference(firstScreening!).inDays;
  }

  // ---- Feature 4: AF burden / intermittency --------------------------------

  int get flaggedCount => scored.where((e) => e.flagged).length;

  /// Fraction of scored screenings that were flagged.
  ///
  /// **This is not the clinical "AF burden".** True AF burden is the proportion
  /// of *time* spent in atrial fibrillation, measured by continuous monitoring.
  /// This is the proportion of *sampled 30-second windows* that were flagged,
  /// from visits that happen days or weeks apart. Call it what it is —
  /// a flag rate across visits — and never quote it as a burden percentage to
  /// a clinician, who will read it as the continuous-monitoring quantity.
  double get flagRate =>
      scored.isEmpty ? 0.0 : flaggedCount / scored.length;

  /// Flagged on some visits but not others — the signature of a paroxysmal
  /// rhythm, and the thing a single clinic ECG is most likely to miss.
  bool get isIntermittent =>
      scored.length >= 2 && flaggedCount > 0 && flaggedCount < scored.length;

  /// Every scored visit flagged, with enough visits to mean something.
  bool get isPersistent =>
      scored.length >= kMinScreeningsForBurden && flaggedCount == scored.length;

  /// Below this many scored screenings, a flag rate is a coin flip described
  /// with a decimal point.
  static const int kMinScreeningsForBurden = 3;

  /// How far apart visits must be before the sample counts as longitudinal
  /// rather than as one sitting.
  static const int kMinObservationDays = 14;

  BurdenConfidence get burdenConfidence {
    if (scored.length < kMinScreeningsForBurden) {
      return BurdenConfidence.insufficient;
    }
    if (observationDays < kMinObservationDays) {
      return BurdenConfidence.provisional;
    }
    return BurdenConfidence.usable;
  }

  // ---- Feature 2: patient-level risk ---------------------------------------

  /// Worst tier ever recorded for this patient.
  String get worstTier {
    if (scored.any((e) => e.tier == 'RED')) return 'RED';
    if (scored.any((e) => e.tier == 'ORANGE')) return 'ORANGE';
    if (scored.any((e) => e.tier == 'YELLOW')) return 'YELLOW';
    if (scored.isNotEmpty) return 'GREEN';
    return 'RETAKE';
  }

  /// True when a clinician has confirmed AF at least once.
  ///
  /// Once this is true the patient is a known case and screening is no longer
  /// the question — follow-up is. The app must not present a GREEN screening as
  /// reassurance for someone already confirmed.
  bool get hasConfirmedFinding =>
      timeline.any((e) => e.outcome == ClinicianOutcome.confirmed);

  /// Referred at least once and the PHC has not closed it.
  bool get hasOpenReferral => timeline.any((e) =>
      e.flagged &&
      e.referralState != null &&
      e.referralState != ReferralState.none &&
      e.referralState != ReferralState.closed);

  /// A patient was referred, but no visit ever reached the PHC.
  ///
  /// This is the number that tells a district officer whether the referral
  /// chain is actually working. A screening programme that flags correctly and
  /// loses every patient before treatment has prevented no strokes at all.
  bool get hasLapsedReferral => timeline.any((e) =>
      e.flagged &&
      (e.referralState == null || e.referralState == ReferralState.none) &&
      DateTime.now().difference(e.capturedAt).inDays > kReferralLapseDays);

  static const int kReferralLapseDays = 14;

  // ---- Feature 12: adaptive repeat measurement -----------------------------

  /// Days until this patient should be screened again.
  ///
  /// Uses only what is already known — the worst tier seen, whether the rhythm
  /// comes and goes, and whether a referral is outstanding. Deliberately
  /// simple, deliberately explainable: a WHV planning her round can be told
  /// exactly why a household is due.
  ///
  /// PROVISIONAL. These intervals are operational judgement, not a validated
  /// schedule, and must be labelled that way (CLAUDE.md non-negotiable 8).
  int get recommendedRepeatDays {
    if (timeline.isEmpty) return 0;
    if (hasConfirmedFinding) return kIntervalConfirmed;
    if (hasOpenReferral) return kIntervalOpenReferral;

    // An intermittent rhythm is precisely the case a single strip misses, so
    // sample it more often. This is the whole longitudinal argument, turned
    // into a scheduling decision.
    if (isIntermittent) return kIntervalIntermittent;

    switch (worstTier) {
      case 'RED':
        return kIntervalAfterRed;
      case 'ORANGE':
        return kIntervalAfterOrange;
      case 'YELLOW':
        return kIntervalAfterYellow;
      case 'RETAKE':
        return kIntervalAfterRetake;
      default:
        return kIntervalRoutine;
    }
  }

  static const int kIntervalConfirmed = 90;
  static const int kIntervalOpenReferral = 14;
  static const int kIntervalIntermittent = 30;
  static const int kIntervalAfterRed = 14;
  static const int kIntervalAfterOrange = 21;
  static const int kIntervalAfterYellow = 45;
  static const int kIntervalAfterRetake = 7;
  static const int kIntervalRoutine = 180;

  /// Days until the next screening is due. Negative means overdue.
  int? get daysUntilDue {
    final last = lastScreening;
    if (last == null) return null;
    final due = last.add(Duration(days: recommendedRepeatDays));
    return due.difference(DateTime.now()).inDays;
  }

  bool get isDue => (daysUntilDue ?? 1) <= 0;

  /// Machine-readable reason the interval is what it is, for the UI and the
  /// dashboard. Never a condition name.
  String get repeatReasonKey {
    if (timeline.isEmpty) return 'never_screened';
    if (hasConfirmedFinding) return 'under_clinician_care';
    if (hasOpenReferral) return 'referral_open';
    if (isIntermittent) return 'varies_between_visits';
    switch (worstTier) {
      case 'RED':
        return 'previous_urgent_referral';
      case 'ORANGE':
        return 'previous_repeated_finding';
      case 'YELLOW':
        return 'previous_referral';
      case 'RETAKE':
        return 'last_capture_unusable';
      default:
        return 'routine';
    }
  }

  Map<String, dynamic> toJson() => {
        'patientPseudoId': patientPseudoId,
        'totalScreenings': totalScreenings,
        'scoredScreenings': scored.length,
        'retakeCount': retakeCount,
        'firstScreening': firstScreening?.toUtc().toIso8601String(),
        'lastScreening': lastScreening?.toUtc().toIso8601String(),
        'observationDays': observationDays,
        'flaggedCount': flaggedCount,
        'flagRate': flagRate,
        'burdenConfidence': burdenConfidence.name,
        'isIntermittent': isIntermittent,
        'isPersistent': isPersistent,
        'worstTier': worstTier,
        'hasConfirmedFinding': hasConfirmedFinding,
        'hasOpenReferral': hasOpenReferral,
        'hasLapsedReferral': hasLapsedReferral,
        'recommendedRepeatDays': recommendedRepeatDays,
        'daysUntilDue': daysUntilDue,
        'isDue': isDue,
        'repeatReasonKey': repeatReasonKey,
      };

  /// Groups a mixed set of records into one history per patient.
  static Map<String, PatientHistory> groupAll(
      Iterable<ScreeningRecord> records) {
    final ids = <String>{for (final r in records) r.patientPseudoId};
    return {
      for (final id in ids) id: PatientHistory.fromRecords(id, records),
    };
  }

  /// Patients due for a repeat visit, most overdue first — the WHV's round.
  static List<PatientHistory> dueList(Iterable<ScreeningRecord> records) {
    final all = groupAll(records).values.where((h) => h.isDue).toList();
    all.sort((a, b) =>
        (a.daysUntilDue ?? 0).compareTo(b.daysUntilDue ?? 0));
    return all;
  }

  static int clampDays(int d) => math.max(0, d);
}
