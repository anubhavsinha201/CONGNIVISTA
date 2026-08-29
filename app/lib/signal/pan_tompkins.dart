import 'dart:math' as math;
import 'dart:typed_data';

import 'filters.dart';

class RPeakResult {
  /// Sample indices of detected R peaks, ascending.
  final List<int> peaks;

  /// The 5-15 Hz zero-phase bandpassed signal the peaks were refined against.
  final Float64List qrsBand;

  /// Moving-window-integrated energy envelope, for debugging and for the
  /// waveform overlay on the capture screen.
  final Float64List integrated;

  const RPeakResult(this.peaks, this.qrsBand, this.integrated);

  /// RR intervals in milliseconds.
  Float64List rrIntervalsMs(double fs) {
    if (peaks.length < 2) return Float64List(0);
    final rr = Float64List(peaks.length - 1);
    for (var i = 0; i < rr.length; i++) {
      rr[i] = (peaks[i + 1] - peaks[i]) * 1000.0 / fs;
    }
    return rr;
  }
}

/// Pan-Tompkins QRS detection, adapted for offline analysis of a captured window.
///
/// Two deliberate deviations from the 1985 paper:
///
///  * The bandpass and the moving-window integrator are zero-phase
///    (forward-backward, and centred). The original is causal because it ran
///    on 1985 hardware in real time. We analyse a complete 30 s window, so we
///    can remove the group delay entirely instead of compensating for it.
///    That matters more here than in a general-purpose monitor: RR interval
///    timing IS the AF signal, so a systematic time shift is error injected
///    straight into the quantity being measured.
///
///  * Peak locations are refined against the bandpassed signal rather than
///    read off the integrator, which only resolves QRS position to roughly
///    its own window width.
class PanTompkins {
  final double fs;

  late final int _mwiWindow; // 150 ms integration window
  late final int _refractory; // 200 ms physiological blanking
  late final int _tWaveWindow; // 360 ms T-wave discrimination limit
  late final int _refineWindow; // +/- 60 ms peak refinement search

  PanTompkins(this.fs) {
    _mwiWindow = math.max(1, (0.150 * fs).round());
    _refractory = (0.200 * fs).round();
    _tWaveWindow = (0.360 * fs).round();
    _refineWindow = math.max(1, (0.060 * fs).round());
  }

  RPeakResult detect(Float64List raw) {
    if (raw.length < fs.toInt()) {
      return RPeakResult(const [], Float64List(0), Float64List(0));
    }

    final band = FilterChain.qrsBand(fs).filtfilt(raw);
    final deriv = _derivative(band);
    final squared = Float64List(deriv.length);
    for (var i = 0; i < deriv.length; i++) {
      squared[i] = deriv[i] * deriv[i];
    }
    final integrated = _centredMovingAverage(squared, _mwiWindow);

    final candidates = _localMaxima(integrated, _refractory);
    final accepted = _adaptiveThreshold(integrated, deriv, candidates);
    final refined = _refineToRPeaks(band, accepted);

    return RPeakResult(refined, band, integrated);
  }

  /// Five-point derivative from the original paper: emphasises the steep QRS
  /// slope while suppressing the slower P and T waves.
  Float64List _derivative(Float64List x) {
    final out = Float64List(x.length);
    for (var i = 4; i < x.length; i++) {
      out[i] = (2 * x[i] + x[i - 1] - x[i - 3] - 2 * x[i - 4]) / 8.0;
    }
    return out;
  }

  /// Centred (zero-phase) moving average, computed with a running sum so the
  /// cost is O(n) rather than O(n * window).
  Float64List _centredMovingAverage(Float64List x, int window) {
    final out = Float64List(x.length);
    if (x.isEmpty) return out;
    final half = window ~/ 2;
    var sum = 0.0;
    var lo = -half;
    var hi = lo + window - 1;
    for (var i = math.max(0, lo); i <= math.min(hi, x.length - 1); i++) {
      sum += x[i];
    }
    for (var i = 0; i < x.length; i++) {
      if (i > 0) {
        lo = i - half;
        hi = lo + window - 1;
        if (hi < x.length) sum += x[hi];
        if (lo - 1 >= 0) sum -= x[lo - 1];
      }
      final count = math.min(hi, x.length - 1) - math.max(lo, 0) + 1;
      out[i] = count > 0 ? sum / count : 0.0;
    }
    return out;
  }

  /// Local maxima separated by at least [minDistance] samples.
  List<int> _localMaxima(Float64List x, int minDistance) {
    final peaks = <int>[];
    for (var i = 1; i < x.length - 1; i++) {
      if (x[i] > x[i - 1] && x[i] >= x[i + 1]) {
        if (peaks.isNotEmpty && i - peaks.last < minDistance) {
          // Keep only the taller of two peaks inside the blanking period.
          if (x[i] > x[peaks.last]) peaks[peaks.length - 1] = i;
        } else {
          peaks.add(i);
        }
      }
    }
    return peaks;
  }

  /// The adaptive dual-threshold rule, with T-wave rejection and searchback.
  List<int> _adaptiveThreshold(
      Float64List integrated, Float64List deriv, List<int> candidates) {
    if (candidates.isEmpty) return const [];

    // Initialise from the first two seconds of signal, as the paper specifies.
    final learnEnd = math.min(integrated.length, (2 * fs).toInt());
    var maxLearn = 0.0, sumLearn = 0.0;
    for (var i = 0; i < learnEnd; i++) {
      if (integrated[i] > maxLearn) maxLearn = integrated[i];
      sumLearn += integrated[i];
    }
    var spki = maxLearn / 3.0;
    var npki = (sumLearn / math.max(1, learnEnd)) / 2.0;
    var t1 = npki + 0.25 * (spki - npki);

    final qrs = <int>[];
    final rrRecent = <double>[];
    var rrAverage = 0.0;

    void acceptPeak(int idx, double amp, {required bool viaSearchback}) {
      if (qrs.isNotEmpty) {
        rrRecent.add((idx - qrs.last).toDouble());
        if (rrRecent.length > 8) rrRecent.removeAt(0);
        rrAverage = rrRecent.reduce((a, b) => a + b) / rrRecent.length;
      }
      qrs.add(idx);
      // A searchback detection is weaker evidence, so it updates the running
      // signal-peak estimate at a quarter weight, per the paper.
      spki =
          viaSearchback ? 0.25 * amp + 0.75 * spki : 0.125 * amp + 0.875 * spki;
    }

    for (var ci = 0; ci < candidates.length; ci++) {
      final idx = candidates[ci];
      final amp = integrated[idx];

      // Searchback: an implausibly long gap usually means a beat was missed
      // because it fell between the two thresholds, so re-examine the gap at t2.
      if (qrs.isNotEmpty && rrAverage > 0 && (idx - qrs.last) > 1.66 * rrAverage) {
        final t2 = 0.5 * t1;
        var bestIdx = -1;
        var bestAmp = 0.0;
        for (var k = ci - 1; k >= 0 && candidates[k] > qrs.last; k--) {
          final c = candidates[k];
          if (c - qrs.last < _refractory) continue;
          if (integrated[c] > t2 && integrated[c] > bestAmp) {
            bestAmp = integrated[c];
            bestIdx = c;
          }
        }
        if (bestIdx >= 0) acceptPeak(bestIdx, bestAmp, viaSearchback: true);
      }

      if (amp > t1) {
        if (qrs.isNotEmpty && (idx - qrs.last) < _tWaveWindow) {
          // Too soon to be a new beat. A T wave rises more slowly than a QRS,
          // so compare maximum slope against the previous accepted beat.
          if (_maxSlope(deriv, idx) < 0.5 * _maxSlope(deriv, qrs.last)) {
            npki = 0.125 * amp + 0.875 * npki;
            t1 = npki + 0.25 * (spki - npki);
            continue;
          }
        }
        if (qrs.isNotEmpty && (idx - qrs.last) < _refractory) continue;
        acceptPeak(idx, amp, viaSearchback: false);
      } else {
        npki = 0.125 * amp + 0.875 * npki;
      }
      t1 = npki + 0.25 * (spki - npki);
    }

    qrs.sort();
    return qrs;
  }

  double _maxSlope(Float64List deriv, int centre) {
    final lo = math.max(0, centre - _refineWindow);
    final hi = math.min(deriv.length - 1, centre + _refineWindow);
    var best = 0.0;
    for (var i = lo; i <= hi; i++) {
      final a = deriv[i].abs();
      if (a > best) best = a;
    }
    return best;
  }

  /// The integrator resolves a QRS only to about its own window width, so take
  /// the true fiducial point as the largest absolute deflection in the
  /// bandpassed signal nearby.
  List<int> _refineToRPeaks(Float64List band, List<int> approx) {
    final out = <int>[];
    for (final idx in approx) {
      final lo = math.max(0, idx - _refineWindow);
      final hi = math.min(band.length - 1, idx + _refineWindow);
      var bestIdx = idx;
      var bestVal = -1.0;
      for (var i = lo; i <= hi; i++) {
        final v = band[i].abs();
        if (v > bestVal) {
          bestVal = v;
          bestIdx = i;
        }
      }
      if (out.isEmpty || bestIdx > out.last) out.add(bestIdx);
    }
    return out;
  }
}
