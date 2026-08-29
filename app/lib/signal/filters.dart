import 'dart:math' as math;
import 'dart:typed_data';

/// Second-order IIR section, direct-form II transposed.
///
/// Coefficients follow the RBJ audio-EQ cookbook, normalised so a0 == 1.
/// We build every filter in this project from cascaded biquads rather than a
/// high-order polynomial because biquads stay numerically well-conditioned at
/// the very low corner frequencies an ECG highpass needs (0.5 Hz at 250 Hz fs
/// is a pole extremely close to the unit circle).
class Biquad {
  final double b0, b1, b2, a1, a2;
  double _s1 = 0, _s2 = 0;

  Biquad._(this.b0, this.b1, this.b2, this.a1, this.a2);

  factory Biquad.lowPass(double fs, double f0, {double q = math.sqrt1_2}) {
    final w0 = 2 * math.pi * f0 / fs;
    final cw = math.cos(w0), alpha = math.sin(w0) / (2 * q);
    final a0 = 1 + alpha;
    return Biquad._(
      (1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0,
      -2 * cw / a0, (1 - alpha) / a0,
    );
  }

  factory Biquad.highPass(double fs, double f0, {double q = math.sqrt1_2}) {
    final w0 = 2 * math.pi * f0 / fs;
    final cw = math.cos(w0), alpha = math.sin(w0) / (2 * q);
    final a0 = 1 + alpha;
    return Biquad._(
      (1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0,
      -2 * cw / a0, (1 - alpha) / a0,
    );
  }

  /// Band-stop at [f0]. Use a high [q] (~30) for mains hum so the notch is
  /// narrow enough not to eat QRS energy either side of it.
  factory Biquad.notch(double fs, double f0, {double q = 30}) {
    final w0 = 2 * math.pi * f0 / fs;
    final cw = math.cos(w0), alpha = math.sin(w0) / (2 * q);
    final a0 = 1 + alpha;
    return Biquad._(
      1 / a0, -2 * cw / a0, 1 / a0,
      -2 * cw / a0, (1 - alpha) / a0,
    );
  }

  /// Sets the internal state to the steady state for a constant input [x0].
  ///
  /// Without this, every filtering pass starts from zero state and rings for
  /// the first fraction of a second. On a 30 s window that transient is a
  /// large spurious deflection at the start, and Pan-Tompkins will happily
  /// report it as an R peak — inventing a beat that never happened.
  void reset(double x0) {
    final gain = b0 + b1 + b2;
    final denom = 1 + a1 + a2;
    final y0 = denom.abs() < 1e-12 ? 0.0 : x0 * gain / denom;
    _s2 = b2 * x0 - a2 * y0;
    _s1 = b1 * x0 - a1 * y0 + _s2;
  }

  double process(double x) {
    final y = b0 * x + _s1;
    _s1 = b1 * x - a1 * y + _s2;
    _s2 = b2 * x - a2 * y;
    return y;
  }
}

/// A cascade of [Biquad] sections applied as one filter.
class FilterChain {
  final List<Biquad> sections;
  const FilterChain(this.sections);

  /// Standard ECG conditioning: 0.5 Hz highpass (baseline wander),
  /// 40 Hz lowpass (EMG and high-frequency noise), 50 Hz notch (Indian mains).
  factory FilterChain.ecgConditioning(double fs) => FilterChain([
        Biquad.highPass(fs, 0.5),
        Biquad.lowPass(fs, 40),
        Biquad.notch(fs, 50),
      ]);

  /// Pan-Tompkins QRS band: 5-15 Hz, where QRS energy dominates P and T waves.
  factory FilterChain.qrsBand(double fs) => FilterChain([
        Biquad.highPass(fs, 5),
        Biquad.lowPass(fs, 15),
      ]);

  /// Pulsatile band for contact PPG: 0.5-5 Hz.
  ///
  /// The lower corner strips the DC and the slow drift from finger pressure and
  /// venous pooling; the upper keeps the systolic upstroke sharp enough to time
  /// accurately while rejecting sensor noise. 5 Hz still admits 300 bpm, far
  /// beyond anything physiological, so nothing real is lost.
  ///
  /// Perfusion index must be computed BEFORE this runs — it is a ratio of the
  /// pulsatile amplitude to the DC level, and this chain removes the DC.
  factory FilterChain.ppgBand(double fs) => FilterChain([
        Biquad.highPass(fs, 0.5),
        Biquad.lowPass(fs, 5),
      ]);

  void _resetAll(double x0) {
    for (final s in sections) {
      s.reset(x0);
    }
  }

  Float64List _forward(Float64List x) {
    if (x.isEmpty) return x;
    final out = Float64List.fromList(x);
    for (final s in sections) {
      s.reset(out[0]);
      for (var i = 0; i < out.length; i++) {
        out[i] = s.process(out[i]);
      }
    }
    return out;
  }

  /// Zero-phase filtering: forward pass, reverse, forward pass, reverse.
  ///
  /// We can afford the non-causal version because we analyse a complete
  /// captured window, not a live stream. It matters: a causal filter shifts
  /// R peaks in time by its group delay, and since the entire AF signal is
  /// the *timing* between R peaks, any frequency-dependent delay is a
  /// systematic error in exactly the quantity we care about.
  Float64List filtfilt(Float64List x) {
    if (x.length < 4) return Float64List.fromList(x);

    // Odd-reflection padding, mirroring scipy.signal.filtfilt, so the filter
    // does not see an artificial step at either end of the window.
    final padLen = math.min(x.length - 1, 750); // 3 s at 250 Hz
    final n = x.length;
    final padded = Float64List(n + 2 * padLen);
    for (var i = 0; i < padLen; i++) {
      padded[i] = 2 * x[0] - x[padLen - i];
      padded[padLen + n + i] = 2 * x[n - 1] - x[n - 2 - i];
    }
    padded.setRange(padLen, padLen + n, x);

    var y = _forward(padded);
    y = Float64List.fromList(y.reversed.toList());
    y = _forward(y);
    y = Float64List.fromList(y.reversed.toList());

    return Float64List.sublistView(y, padLen, padLen + n);
  }

  /// Causal, stateful filtering for live display. Cheaper, and phase
  /// distortion is irrelevant when the output is only being drawn on screen.
  Float64List filterStreaming(Float64List x, {bool resetState = false}) {
    if (resetState && x.isNotEmpty) _resetAll(x[0]);
    final out = Float64List.fromList(x);
    for (final s in sections) {
      for (var i = 0; i < out.length; i++) {
        out[i] = s.process(out[i]);
      }
    }
    return out;
  }
}
