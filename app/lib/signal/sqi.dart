import 'dart:math' as math;
import 'dart:typed_data';

import 'filters.dart';

/// Signal quality assessment for a captured ECG window.
class SqiResult {
  /// Overall 0-1 quality. The tier policy gates on this.
  final double score;

  /// Fraction of samples at or near the ADC rail.
  final double saturationFraction;

  /// Fraction of samples inside a run of no change (disconnected lead, or a
  /// stuck ADC).
  final double flatlineFraction;

  /// Power in the 48-52 Hz mains band as a fraction of total signal power.
  final double powerlineRatio;

  /// Power below 0.5 Hz as a fraction of total power — breathing, electrode
  /// movement, cable sway.
  final double baselineWanderRatio;

  /// Human-readable reason the window failed, or null if it passed.
  final String? failureReason;

  const SqiResult({
    required this.score,
    required this.saturationFraction,
    required this.flatlineFraction,
    required this.powerlineRatio,
    required this.baselineWanderRatio,
    this.failureReason,
  });

  Map<String, dynamic> toJson() => {
        'score': score,
        'saturationFraction': saturationFraction,
        'flatlineFraction': flatlineFraction,
        'powerlineRatio': powerlineRatio,
        'baselineWanderRatio': baselineWanderRatio,
        'failureReason': failureReason,
      };
}

/// Scores how trustworthy a captured window is, before anything tries to
/// interpret it.
///
/// This exists because of a specific failure mode described in
/// docs/PRODUCT.md section 5.4: a poor-contact trace that gets scored anyway
/// becomes a false referral, and false referrals are what discredit community
/// screening programmes and get them shut down. Refusing to answer is a
/// product feature, not an error path.
class SqiAnalyser {
  final double fs;

  /// Samples arrive as ADC value minus 2048 (see contracts/ble.md), so the
  /// rails sit at +/- 2048. Treat anything within 8 counts as clipped.
  static const double railMagnitude = 2040;

  /// A run of identical samples this long or longer counts as flatline.
  static const double flatlineRunSeconds = 0.05;

  /// Saturated-sample fraction at which the window is fully disqualified.
  ///
  /// Deliberately low. A QRS complex is narrow — roughly 30 ms of an 800 ms
  /// beat — so even severely clipped R-peak tips only reach 2-3% of samples.
  /// A threshold of 5% could therefore never fire on clipping at all; it would
  /// only fire once the whole trace was railed, by which point the flatline
  /// detector has already caught it. Above ~1% the front-end gain is
  /// misconfigured and amplitudes cannot be trusted.
  static const double kSaturationFail = 0.02;

  const SqiAnalyser(this.fs);

  SqiResult analyse(Float64List raw) {
    if (raw.length < fs.toInt()) {
      return const SqiResult(
        score: 0,
        saturationFraction: 0,
        flatlineFraction: 0,
        powerlineRatio: 0,
        baselineWanderRatio: 0,
        failureReason: 'Capture too short to assess',
      );
    }

    final saturation = _saturationFraction(raw);
    final flatline = _flatlineFraction(raw);

    final totalPower = _variance(raw);
    final powerline =
        totalPower > 0 ? _bandPower(raw, 48, 52) / totalPower : 0.0;
    final wander = totalPower > 0 ? _lowFrequencyPower(raw) / totalPower : 0.0;

    // Multiplicative combination: this is a gate, so any single failure must
    // be able to fail the whole window on its own. Averaging would let a
    // pristine baseline mask a completely detached electrode.
    var score = 1.0;
    score *= 1.0 - math.min(1.0, saturation / kSaturationFail);
    score *= 1.0 - math.min(1.0, flatline / 0.10);
    score *= 1.0 - math.min(1.0, powerline / 0.50);
    score *= 1.0 - math.min(1.0, wander / 0.80);
    score = score.clamp(0.0, 1.0);

    String? reason;
    if (flatline >= 0.10) {
      reason = 'Electrode contact lost';
    } else if (saturation >= kSaturationFail) {
      reason = 'Signal clipping - check electrode placement';
    } else if (powerline >= 0.50) {
      reason = 'Mains interference - move away from wiring, unplug the charger';
    } else if (wander >= 0.80) {
      reason = 'Baseline drift - ask the patient to stay still';
    }

    return SqiResult(
      score: score,
      saturationFraction: saturation,
      flatlineFraction: flatline,
      powerlineRatio: powerline,
      baselineWanderRatio: wander,
      failureReason: reason,
    );
  }

  double _saturationFraction(Float64List x) {
    var n = 0;
    for (final v in x) {
      if (v.abs() >= railMagnitude) n++;
    }
    return n / x.length;
  }

  double _flatlineFraction(Float64List x) {
    final minRun = math.max(2, (flatlineRunSeconds * fs).round());
    var flat = 0;
    var runStart = 0;
    for (var i = 1; i <= x.length; i++) {
      final continues = i < x.length && (x[i] - x[i - 1]).abs() < 1e-9;
      if (!continues) {
        final runLen = i - runStart;
        if (runLen >= minRun) flat += runLen;
        runStart = i;
      }
    }
    return flat / x.length;
  }

  double _variance(Float64List x) {
    var mean = 0.0;
    for (final v in x) {
      mean += v;
    }
    mean /= x.length;
    var acc = 0.0;
    for (final v in x) {
      final d = v - mean;
      acc += d * d;
    }
    return acc / x.length;
  }

  /// Power in [loHz, hiHz], summed over DFT bins via the Goertzel algorithm.
  ///
  /// Goertzel rather than a full FFT because we only ever ask about a handful
  /// of narrow bands. It needs no power-of-two length, no buffer allocation,
  /// and no FFT dependency in the app.
  double _bandPower(Float64List x, double loHz, double hiHz) {
    final n = x.length;
    final kLo = (loHz * n / fs).floor();
    final kHi = (hiHz * n / fs).ceil();
    var total = 0.0;
    for (var k = math.max(1, kLo); k <= math.min(kHi, n ~/ 2); k++) {
      total += _goertzelPower(x, k);
    }
    // Parseval scaling, so the result is comparable with a time-domain variance.
    return total / (n * n / 2);
  }

  double _goertzelPower(Float64List x, int k) {
    final w = 2 * math.pi * k / x.length;
    final coeff = 2 * math.cos(w);
    var s1 = 0.0, s2 = 0.0;
    for (final v in x) {
      final s0 = v + coeff * s1 - s2;
      s2 = s1;
      s1 = s0;
    }
    return s1 * s1 + s2 * s2 - coeff * s1 * s2;
  }

  /// Power below 0.5 Hz, obtained as the residual after removing everything
  /// above 0.5 Hz with the same zero-phase highpass used for conditioning.
  ///
  /// An earlier version estimated this with a 2 s moving average. That is
  /// wrong in a subtle way: a moving average has a sinc response, so at
  /// 0.25 Hz it already passes only ~40% of the power it is trying to
  /// measure. The ratio then saturated near 0.55 no matter how severe the
  /// drift, and could never cross a threshold set above that — the detector
  /// silently under-reported exactly the condition it existed to catch.
  double _lowFrequencyPower(Float64List x) {
    final highpassed =
        FilterChain([Biquad.highPass(fs, 0.5)]).filtfilt(x);
    final low = Float64List(x.length);
    for (var i = 0; i < x.length; i++) {
      low[i] = x[i] - highpassed[i];
    }
    return _variance(low);
  }
}
