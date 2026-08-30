package com.arogyax.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arogyax.core.Assistant
import com.arogyax.core.AssistantContext
import com.arogyax.core.EcgQuality
import com.arogyax.core.ExplanationAudio
import com.arogyax.core.MediaPlayerClipPlayer
import com.arogyax.core.QualityBand
import com.arogyax.core.RepeatAction
import com.arogyax.core.RiskTrajectory
import com.arogyax.core.ScreeningPriority
import com.arogyax.core.SequencePlayer
import com.arogyax.core.Tier
import com.arogyax.core.TierAudioClips
import com.arogyax.data.BleEcgSource
import com.arogyax.data.BleState
import com.arogyax.data.EncryptedStore
import com.arogyax.data.ReferralState
import com.arogyax.data.ReplaySource
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The whole worker-facing app, built from framework Views.
 *
 * Screens are rebuilt from [ScreeningController] state on every [render] rather
 * than mutated in place. At this size that is simpler to reason about than a
 * diffing layer, and a screening app is not a scrolling feed — it shows one
 * patient at a time.
 */
class MainActivity : Activity() {

    private lateinit var controller: ScreeningController
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private lateinit var ble: BleEcgSource
    private lateinit var store: EncryptedStore

    /** True when the next capture should come from the sensor, not the replay asset. */
    private var liveMode = true
    private var storeError: String? = null

    /**
     * Spoken Tamil playback. Every clip is generated from UNREVIEWED DRAFT text
     * (`tamil_strings_DRAFT.json`, ticket 011) - never claim this is reviewed
     * copy, and the DRAFT chip stays next to every screen that plays it.
     *
     * Built once and reused: constructing [ExplanationAudio] parses the 33-key
     * manifest, which is wasted work to repeat on every result screen.
     */
    private lateinit var sequencePlayer: SequencePlayer
    private var explanationAudio: ExplanationAudio? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The bundled replay trace: a real, labelled PhysioNet AF recording.
        // CLAUDE.md's demo-integrity rule - AF cannot be induced in a healthy
        // teammate, so the demo replays a real one and says so on screen.
        controller = ScreeningController(
            ReplaySource { assets.open("replay/af_A02501_250hz.raw") },
        )

        ble = BleEcgSource(this)

        // Encrypted at rest, key in the Android Keystore. A load failure is
        // surfaced rather than swallowed: an unreadable store looks exactly like
        // a fresh install, and silently starting empty would hide a real loss.
        store = EncryptedStore(this)
        storeError = store.load()
        controller.onRecorded = { r ->
            store.add(recordJson(r))?.let { storeError = it }
        }

        sequencePlayer = SequencePlayer(MediaPlayerClipPlayer(this))
        explanationAudio = try {
            val text = assets.open("strings/tamil_audio_manifest_DRAFT.json")
                .bufferedReader().use { it.readText() }
            ExplanationAudio(org.json.JSONObject(text))
        } catch (e: Exception) {
            // Missing or malformed manifest must not crash the app - it just
            // means per-reason narration is unavailable; the tier clip alone
            // still plays.
            null
        }

        // fillHeight is load-bearing: the ScrollView below is height=0/weight=1,
        // and weight only divides LEFTOVER space. With a WRAP_CONTENT root there
        // is none, so the scroll area collapsed and only the header rendered.
        root = column(bg = Gov.CANVAS, fillHeight = true)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            isFillViewport = true
        }
        body = column(bg = Gov.CANVAS)
        scroll.addView(
            body,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(header())
        root.addView(scroll)
        setContentView(root)
        render()
    }

    // ---- Chrome ----------------------------------------------------------

    private fun header(): View = column {
        setBackgroundColor(Gov.NAVY)

        // Tricolour rule. Visual language only - this is a prototype and does
        // not claim to be an official product of any department, which is why
        // no emblem or department name appears anywhere in this app.
        add(
            row {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
                val band = ViewGroup.LayoutParams.MATCH_PARENT
                add(View(context).apply { setBackgroundColor(Gov.SAFFRON) }, weight = 1f, height = band)
                add(View(context).apply { setBackgroundColor(Color.WHITE) }, weight = 1f, height = band)
                add(View(context).apply { setBackgroundColor(Gov.INDIA_GREEN) }, weight = 1f, height = band)
            },
        )

        add(
            row(pad = 0) {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                add(
                    column {
                        add(text("ArogyaX", 20f, Color.WHITE, bold = true))
                        add(
                            text(
                                "Cardiac Screening & Referral Triage",
                                12f, Color.parseColor("#A8BEDC"),
                            ),
                            top = 1,
                        )
                    },
                    weight = 1f,
                )
                add(
                    text(
                        if (controller.voiceEnabled) "VOICE" else "MUTED",
                        10.5f, Color.WHITE, bold = true,
                    ).apply {
                        background = roundedFill(Gov.NAVY_LIGHT, dp(3).toFloat())
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener {
                            controller.voiceEnabled = !controller.voiceEnabled
                            if (!controller.voiceEnabled) sequencePlayer.cancel()
                            rebuild()
                        }
                    },
                    matchWidth = false, right = 6,
                )
                add(
                    text(if (controller.online) "ONLINE" else "OFFLINE", 10.5f, Color.WHITE, bold = true)
                        .apply {
                            background = roundedFill(
                                if (controller.online) Gov.INDIA_GREEN else Gov.NAVY_LIGHT,
                                dp(3).toFloat(),
                            )
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                            setOnClickListener { controller.online = !controller.online; rebuild() }
                        },
                    matchWidth = false,
                )
            },
        )

        add(
            text("PROTOTYPE — NOT FOR CLINICAL USE", 10f, Gov.NAVY_DARK, bold = true, spacing = 0.08f)
                .apply {
                    setBackgroundColor(Gov.SAFFRON)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, dp(3))
                },
        )
    }

    private fun rebuild() {
        root.removeViewAt(0)
        root.addView(header(), 0)
        render()
    }

    private fun render() {
        body.removeAllViews()
        when (controller.screen) {
            Screen.HOME -> home()
            Screen.PATIENT -> patientForm()
            Screen.CAPTURE -> captureScreen()
            Screen.RESULT -> resultScreen()
            Screen.TIMELINE -> timelineScreen()
            Screen.REFERRALS -> referralScreen()
            Screen.DISTRICT -> districtScreen()
            Screen.ASSISTANT -> assistantScreen()
        }
    }

    private fun go(s: Screen) {
        controller.screen = s
        render()
    }

    private fun LinearLayout.pageTitle(title: String, subtitle: String? = null, back: Screen? = null) {
        if (back != null) {
            add(
                text("‹  Back", 15f, Gov.NAVY, bold = true).apply {
                    setPadding(0, dp(4), 0, dp(10))
                    setOnClickListener { go(back) }
                },
            )
        }
        add(text(title, 23f, Gov.INK, bold = true))
        subtitle?.let { add(text(it, 14f, Gov.INK_MUTED), top = 3) }
        add(gap(14))
    }

    // ---- Home (spec 7.2) -------------------------------------------------

    private fun home(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))

        pageTitle("Today", "Field screening session")

        add(
            row {
                add(
                    statTile("${controller.patientCount()}", "Patients", Gov.INK),
                    weight = 1f, right = 5,
                )
                add(
                    statTile("${controller.scoredCount()}", "Screened", Gov.INK),
                    weight = 1f, left = 5, right = 5,
                )
                add(
                    statTile("${controller.count(Tier.RETAKE)}", "Repeats", Gov.GREY),
                    weight = 1f, left = 5,
                )
            },
            bottom = 10,
        )
        add(
            row {
                add(
                    statTile("${controller.count(Tier.RED)}", "Priority", Gov.RED),
                    weight = 1f, right = 5,
                )
                add(
                    statTile(
                        "${controller.count(Tier.ORANGE) + controller.count(Tier.YELLOW)}",
                        "Referrals", Gov.ORANGE,
                    ),
                    weight = 1f, left = 5, right = 5,
                )
                add(
                    statTile("${controller.count(Tier.GREEN)}", "Routine", Gov.GREEN),
                    weight = 1f, left = 5,
                )
            },
            bottom = 18,
        )

        add(
            primaryButton("Screen with ECG sensor") {
                liveMode = true
                controller.beginPatient()
                render()
            },
            bottom = 10,
        )
        add(
            secondaryButton("Demo - replay a recording") {
                liveMode = false
                controller.beginPatient()
                render()
            },
            bottom = 10,
        )
        add(secondaryButton("Referral queue (${controller.referralQueue().size})") { go(Screen.REFERRALS) }, bottom = 10)
        add(secondaryButton("District overview") { go(Screen.DISTRICT) }, bottom = 10)
        add(secondaryButton("Help & assistant") { go(Screen.ASSISTANT) }, bottom = 18)

        if (controller.session.isNotEmpty()) {
            add(sectionLabel("Recent screenings"))
            add(
                card(pad = 0) {
                    controller.session.take(6).forEachIndexed { i, r ->
                        if (i > 0) addView(hairline())
                        addView(
                            row(pad = 14) {
                                add(
                                    column {
                                        add(text(r.patient.pseudoId, 15f, Gov.INK, bold = true))
                                        add(
                                            text(
                                                "${r.capturedAt.format(timeFmt)} · ${r.patient.villageCode.ifBlank { "—" }}",
                                                12.5f, Gov.INK_MUTED,
                                            ),
                                            top = 2,
                                        )
                                    },
                                    weight = 1f,
                                )
                                add(tierBadge(r.decision.tier), matchWidth = false)
                            }.apply {
                                setOnClickListener {
                                    controller.current = r
                                    controller.patient = r.patient
                                    go(Screen.RESULT)
                                }
                            },
                        )
                    }
                },
                bottom = 16,
            )
        }

        add(
            card {
                add(sectionLabel("On-device storage"))
                add(
                    dataRow(
                        "Records stored",
                        "${store.size}",
                        if (storeError != null) Gov.RED else Gov.INK,
                        bold = true,
                    ),
                )
                addView(hairline())
                add(dataRow("Encryption", "AES-256-GCM, Keystore"))
                addView(hairline())
                add(dataRow("File size", "${store.sizeOnDiskBytes} bytes"))
                storeError?.let {
                    add(text(it, 13f, Gov.RED, bold = true), top = 8)
                }
                add(
                    text(
                        "Records hold no name, phone or Aadhaar - only a salted screening ID.",
                        12.5f, Gov.INK_FAINT,
                    ),
                    top = 8,
                )
            },
            bottom = 12,
        )

        add(disclaimer())
    }

    private fun statTile(value: String, caption: String, color: Int): LinearLayout = card(pad = 12) {
        add(text(value, 24f, color, bold = true))
        add(text(caption, 12.5f, Gov.INK_MUTED), top = 1)
    }

    // ---- Patient entry (spec 2.2) ----------------------------------------

    private val fields = mutableMapOf<String, EditText>()

    private fun patientForm(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        fields.clear()
        pageTitle("Patient details", "No name, phone or Aadhaar is ever recorded", back = Screen.HOME)

        add(
            card {
                add(sectionLabel("Identity"))
                add(
                    dataRow("Screening ID", controller.patient.pseudoId, Gov.INK, bold = true),
                )
                add(
                    text(
                        "A salted pseudonymous ID. No personally identifying information leaves this device.",
                        12.5f, Gov.INK_FAINT,
                    ),
                    top = 4,
                )
            },
            bottom = 12,
        )

        add(
            card {
                add(sectionLabel("Required"))
                add(field("Age band", "ageBand", "e.g. 60-69"), bottom = 10)
                add(field("Village / locality code", "villageCode", "e.g. TN-VLG-014"))
            },
            bottom = 12,
        )

        add(
            card {
                add(sectionLabel("Optional context"))
                add(field("Sex", "sex", "F / M / O"), bottom = 10)
                add(
                    row {
                        add(field("Systolic BP", "systolicBp", "mmHg"), weight = 1f, right = 5)
                        add(field("Diastolic BP", "diastolicBp", "mmHg"), weight = 1f, left = 5)
                    },
                    bottom = 10,
                )
                add(field("Blood glucose", "glucose", "mg/dL"))
                add(
                    text(
                        "Recorded for the clinician's context. These do not change the screening result.",
                        12.5f, Gov.INK_FAINT,
                    ),
                    top = 8,
                )
            },
            bottom = 16,
        )

        add(
            primaryButton("Continue to recording") {
                controller.patient.ageBand = fields["ageBand"]!!.text.toString().trim()
                controller.patient.villageCode = fields["villageCode"]!!.text.toString().trim()
                controller.patient.sex = fields["sex"]!!.text.toString().trim().ifBlank { null }
                controller.patient.systolicBp = fields["systolicBp"]!!.text.toString().trim()
                controller.patient.diastolicBp = fields["diastolicBp"]!!.text.toString().trim()
                controller.patient.glucose = fields["glucose"]!!.text.toString().trim()

                if (!controller.patient.complete) {
                    toast("Age band and village code are required")
                } else {
                    go(Screen.CAPTURE)
                }
            },
        )
    }

    private fun field(label: String, key: String, hint: String): LinearLayout = column {
        add(text(label, 13f, Gov.INK_MUTED), bottom = 4)
        val e = EditText(context).apply {
            this.hint = hint
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Gov.INK)
            setHintTextColor(Gov.INK_FAINT)
            background = roundedFill(Gov.SURFACE, dp(4).toFloat(), dp(1), Gov.HAIRLINE)
            setPadding(dp(12), dp(13), dp(12), dp(13))
            inputType = if (key in setOf("systolicBp", "diastolicBp", "glucose")) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
        }
        fields[key] = e
        add(e)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- Capture: live BLE, or the bundled replay ------------------------

    private var liveTrace: LiveTraceView? = null

    /** BLE needs runtime permission; which ones depends on the Android version. */
    private fun blePermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Before Android 12 a BLE scan is gated behind location, however
            // little sense that makes for reading an ECG over a cable-free wire.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBlePermissions(): Boolean = blePermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLiveCapture()
            } else {
                toast("Bluetooth permission is needed to read the sensor")
                render()
            }
        }
    }

    private fun captureScreen(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        pageTitle(
            if (liveMode) "Recording - live sensor" else "Recording - replay",
            controller.patient.pseudoId,
            back = Screen.PATIENT,
        )

        val trace = LiveTraceView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
        }
        liveTrace = trace

        val statusLine = text("", 15f, Gov.INK_MUTED).apply { gravity = Gravity.CENTER }
        val bigCount = text("30", 44f, Gov.NAVY, bold = true).apply { gravity = Gravity.CENTER }
        val meter = MeterView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }
        val leadWarn = text("", 14f, Gov.RED, bold = true)

        add(
            card {
                add(sectionLabel("Live trace"))
                add(trace, bottom = 6)
                add(
                    text(
                        "Amplitude is arbitrary units, not millivolts - nothing here is " +
                            "calibrated against a reference.",
                        12f, Gov.INK_FAINT,
                    ),
                )
            },
            bottom = 12,
        )

        add(
            card {
                add(bigCount)
                add(statusLine, top = 2, bottom = 10)
                add(meter, bottom = 8)
                add(leadWarn)
            },
            bottom = 12,
        )

        if (controller.voiceEnabled) {
            add(
                card {
                    add(
                        row {
                            add(sectionLabel("Spoken guidance (Tamil)"), weight = 1f)
                            add(draftChip(), matchWidth = false)
                        },
                        bottom = 6,
                    )
                    add(text(TAMIL_ELECTRODE_HINT, 16f, Gov.INK))
                    add(text("Attach the electrodes correctly.", 13f, Gov.INK_MUTED), top = 2)
                },
                bottom = 12,
            )
        }

        add(disclaimer(), bottom = 12)

        if (liveMode) {
            add(
                primaryButton("Try again") {
                    ble.close()
                    // close() is asynchronous now - it waits for the peripheral
                    // to confirm the disconnect before releasing the client, so
                    // the retry has to start after that, not on top of it.
                    handler.postDelayed({ render() }, 900)
                },
                bottom = 10,
            )
        }
        add(
            secondaryButton("Cancel") {
                ble.close()
                handler.removeCallbacksAndMessages(null)
                go(Screen.PATIENT)
            },
        )

        if (!liveMode) {
            replayCapture(trace, bigCount, statusLine, meter)
            return
        }

        // ---- Live path -----------------------------------------------------
        ble.onState = { st, msg ->
            statusLine.text = when (st) {
                BleState.SCANNING -> "Looking for the sensor unit..."
                BleState.CONNECTING -> "Connecting..."
                BleState.READY -> "Connected - starting the recording"
                BleState.STREAMING -> "Recording - keep the electrodes still"
                BleState.COMPLETE -> "Recording complete"
                BleState.FAILED -> msg ?: "Could not read the sensor"
                BleState.IDLE -> ""
            }
            if (st == BleState.FAILED) {
                bigCount.text = "--"
                statusLine.setTextColor(Gov.RED)
            }
        }
        ble.onStatus = { st ->
            leadWarn.text = if (st.leadOff) {
                "Electrode off the skin - reattach before the recording can be used"
            } else {
                ""
            }
        }
        ble.onSamples = { trace.append(it) }
        ble.onProgress = { p ->
            meter.set(p, Gov.NAVY)
            bigCount.text = ((1.0 - p) * 30).toInt().coerceAtLeast(0).toString()
        }
        ble.onWindow = { window, gap ->
            val st = ble.status
            controller.analyse(window, dataGap = gap, leadOff = st?.leadOff ?: false)
            ble.close()
            render()
        }

        if (!hasBlePermissions()) {
            requestPermissions(blePermissions(), REQ_BLE)
        } else {
            startLiveCapture()
        }
    }

    private fun startLiveCapture() {
        if (!ble.bluetoothReady) {
            toast("Turn Bluetooth on, then try again")
            return
        }
        ble.startCapture()
    }

    /** The bundled AF recording, stepped through so the flow is demonstrable. */
    private fun replayCapture(
        trace: LiveTraceView,
        bigCount: TextView,
        statusLine: TextView,
        meter: MeterView,
    ) {
        val full = ReplaySource { assets.open("replay/af_A02501_250hz.raw") }.captureEcg()
        statusLine.text = "Replaying a real labelled AF recording - not a live capture"

        var frame = 0
        val frames = full.size / 25
        val tick = object : Runnable {
            override fun run() {
                val from = frame * 25
                val to = (from + 25).coerceAtMost(full.size)
                if (from < to) trace.append(full.copyOfRange(from, to))
                frame++
                val p = frame.toDouble() / frames
                meter.set(p, Gov.NAVY)
                bigCount.text = ((1.0 - p) * 30).toInt().coerceAtLeast(0).toString()
                if (frame < frames) {
                    handler.postDelayed(this, 33)
                } else {
                    controller.analyse(full)
                    render()
                }
            }
        }
        handler.postDelayed(tick, 33)
    }

    // ---- Result (spec 3, 12, 13, 15.2, 16) --------------------------------

    private fun resultScreen(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        val r = controller.current ?: run { go(Screen.HOME); return }
        val tier = r.decision.tier

        pageTitle("Result", "${r.patient.pseudoId} · ${r.capturedAt.format(timeFmt)}", back = Screen.HOME)

        // Spoken result. Autoplays once per screen build (render() rebuilds the
        // whole screen from scratch, so this runs exactly when the result first
        // appears - "Next patient" or "Record again" both leave this screen and
        // come back to a fresh one, which starts a fresh sequence).
        if (controller.voiceEnabled) speakResult(r)

        // Verdict
        add(
            column(pad = 18) {
                background = roundedFill(Gov.fill(tier), dp(4).toFloat(), dp(1), Gov.ink(tier))
                add(tierBadge(tier, large = true), matchWidth = false)
                add(text(Gov.timeframe(tier), 17f, Gov.ink(tier), bold = true), top = 10)
                if (tier != Tier.RETAKE) {
                    add(
                        text(
                            "Screening priority: ${priorityLabel(r.risk.priority)}" +
                                if (r.risk.raisedByHistory) "  (raised by earlier visits)" else "",
                            13.5f, Gov.INK_MUTED,
                        ),
                        top = 6,
                    )
                }
            },
            bottom = 12,
        )

        // Adaptive repeat (spec 13)
        r.repeat?.let { g ->
            val escalated = g.action == RepeatAction.ESCALATE
            add(
                card {
                    add(sectionLabel(if (escalated) "Stop and refer" else "What to change"))
                    add(text(g.instruction, 15.5f, Gov.INK))
                    add(
                        text(
                            "Attempt ${g.attempt} of ${com.arogyax.core.AdaptiveRepeat.MAX_ATTEMPTS}",
                            12.5f, Gov.INK_FAINT,
                        ),
                        top = 6,
                    )
                },
                bottom = 12,
            )
            if (g.shouldCaptureAgain) {
                add(primaryButton("Record again") { go(Screen.CAPTURE) }, bottom = 12)
            }
        }

        // Quality panel (spec 12)
        val q = r.quality
        val qc = when (q.band) {
            QualityBand.GOOD -> Gov.GREEN; QualityBand.FAIR -> Gov.YELLOW; QualityBand.POOR -> Gov.RED
        }
        add(
            card {
                add(
                    row {
                        add(sectionLabel("Recording quality"), weight = 1f)
                        add(text("${q.percent}%", 17f, qc, bold = true), matchWidth = false)
                    },
                    bottom = 10,
                )
                q.factors.forEach { f ->
                    val c = when (f.band) {
                        QualityBand.GOOD -> Gov.GREEN; QualityBand.FAIR -> Gov.YELLOW
                        QualityBand.POOR -> Gov.RED
                    }
                    addView(meterRow(f.label, f.score, c))
                }
                q.failureReason?.let {
                    add(text(it, 13.5f, Gov.RED), top = 2)
                }
            },
            bottom = 12,
        )

        // Measurements
        add(
            card {
                add(sectionLabel("Measurements"))
                add(dataRow("Heart rate", "${r.inputs.meanHr.roundToInt()} bpm"))
                addView(hairline())
                add(dataRow("Beats detected", "${r.inputs.rrIntervalCount + 1}"))
                addView(hairline())
                add(dataRow("Rhythm irregularity", fmt(r.inputs.rrIrregularityScore)))
                addView(hairline())
                add(dataRow("Signal quality", fmt(r.inputs.sqiScore)))
                addView(hairline())
                add(dataRow("Decided by", decidedByLabel(r.decision.decidedBy.wire)))
            },
            bottom = 12,
        )

        // Trace
        add(
            card {
                add(sectionLabel("Recording"))
                add(
                    EcgTraceView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(130),
                        )
                        setTrace(r.waveform, r.peaks)
                    },
                )
                add(text("30 seconds · orange marks are detected beats", 12f, Gov.INK_FAINT), top = 6)
            },
            bottom = 12,
        )

        // Explainability (spec 15.2)
        if (r.reasons.isNotEmpty() || r.risk.reasons.isNotEmpty()) {
            add(
                card {
                    add(sectionLabel("Why this result"))
                    r.reasons.forEach { add(bullet(Assistant.readable(it)), top = 3) }
                    if (r.risk.reasons.isNotEmpty()) {
                        add(View(context), top = 6)
                        add(text("From earlier visits", 12.5f, Gov.INK_FAINT, bold = true), top = 4, bottom = 2)
                        r.risk.reasons.forEach { add(bullet(Assistant.readable(it)), top = 3) }
                    }
                    add(
                        text("Trajectory: ${trajectoryLabel(r.risk.trajectory)}", 13f, Gov.INK_MUTED),
                        top = 10,
                    )
                },
                bottom = 12,
            )
        }

        // Tamil result string
        add(
            card {
                add(
                    row {
                        add(sectionLabel("Spoken result (Tamil)"), weight = 1f)
                        add(draftChip(), matchWidth = false)
                    },
                    bottom = 6,
                )
                add(text(tamilFor(tier), 16f, Gov.INK))
                add(text("இது ஒரு பரிசோதனை மட்டுமே. நோய் கண்டறிதல் அல்ல.", 13.5f, Gov.INK_MUTED), top = 6)
                add(
                    secondaryButton(if (controller.voiceEnabled) "Play again" else "Turn voice on and play") {
                        controller.voiceEnabled = true
                        speakResult(r)
                        rebuild()
                    },
                    top = 10,
                )
            },
            bottom = 12,
        )

        add(disclaimer(), bottom = 14)

        if (r.referrable) {
            add(
                primaryButton("Queue referral", Gov.ink(tier)) {
                    r.referralState = ReferralState.ACKNOWLEDGED
                    toast("Referral queued — will sync when a network appears")
                    render()
                },
                bottom = 10,
            )
        }
        add(secondaryButton("Patient timeline") { go(Screen.TIMELINE) }, bottom = 10)
        add(secondaryButton("Next patient") { controller.beginPatient(); render() })
    }

    private fun bullet(s: String): LinearLayout = row {
        gravity = Gravity.TOP
        add(text("•", 15f, Gov.NAVY, bold = true), matchWidth = false)
        add(text(s, 14f, Gov.INK), weight = 1f, left = 8)
    }

    // ---- Timeline (spec 6) ------------------------------------------------

    private fun timelineScreen(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        val id = controller.current?.patient?.pseudoId ?: controller.patient.pseudoId
        val h = controller.historyFor(id)
        pageTitle("Patient timeline", id, back = Screen.RESULT)

        if (h.timeline.isEmpty()) {
            add(card { add(text("No screenings recorded for this patient yet.", 14f, Gov.INK_MUTED)) })
            return
        }

        add(
            card {
                add(sectionLabel("Pattern across visits"))
                add(
                    TierStripView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
                        set(h.timeline.reversed().map { Gov.ink(tierOf(it.tier)) to Gov.fill(tierOf(it.tier)) })
                    },
                    bottom = 10,
                )
                add(dataRow("Screenings", "${h.totalScreenings}"))
                addView(hairline())
                add(dataRow("Flagged", "${h.flaggedCount} of ${h.scored.size} scored"))
                addView(hairline())
                add(dataRow("Repeat captures", "${h.retakeCount}"))
                addView(hairline())
                add(
                    dataRow(
                        "Pattern",
                        when {
                            h.isPersistent -> "Flagged every visit"
                            h.isIntermittent -> "Comes and goes"
                            else -> "No repeated pattern"
                        },
                    ),
                )
                addView(hairline())
                add(dataRow("Confidence", h.burdenConfidence.name.lowercase().replaceFirstChar { it.uppercase() }))
                add(
                    text(
                        "Repeated screening abnormality — not a measure of clinical AF burden, " +
                            "which needs continuous monitoring this device does not do.",
                        12.5f, Gov.INK_FAINT,
                    ),
                    top = 8,
                )
            },
            bottom = 12,
        )

        add(sectionLabel("Visits"))
        add(
            card(pad = 0) {
                h.timeline.forEachIndexed { i, e ->
                    if (i > 0) addView(hairline())
                    addView(
                        row(pad = 14) {
                            add(
                                column {
                                    add(text(e.capturedAt.format(timeFmt), 14.5f, Gov.INK, bold = true))
                                    add(
                                        text(
                                            listOfNotNull(
                                                e.meanHr?.let { "${it.roundToInt()} bpm" },
                                                e.rrIrregularityScore?.let { "irregularity ${fmt(it)}" },
                                            ).joinToString(" · "),
                                            12.5f, Gov.INK_MUTED,
                                        ),
                                        top = 2,
                                    )
                                },
                                weight = 1f,
                            )
                            add(tierBadge(tierOf(e.tier)), matchWidth = false)
                        },
                    )
                }
            },
            bottom = 12,
        )
        add(disclaimer())
    }

    // ---- Referral queue + follow-up (spec 7.3, 14) ------------------------

    private fun referralScreen(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        val queue = controller.referralQueue()
        pageTitle("Referrals", "Worst first, as a clinician would work the list", back = Screen.HOME)

        add(
            row {
                add(statTile("${controller.pendingReferrals()}", "Pending", Gov.ORANGE), weight = 1f, right = 5)
                add(statTile("${controller.completedReferrals()}", "Closed", Gov.GREEN), weight = 1f, left = 5, right = 5)
                add(statTile("${controller.count(Tier.RED)}", "Priority", Gov.RED), weight = 1f, left = 5)
            },
            bottom = 14,
        )

        if (queue.isEmpty()) {
            add(card { add(text("No referrals yet in this session.", 14f, Gov.INK_MUTED)) })
            return
        }

        queue.forEach { r ->
            add(
                card {
                    add(
                        row {
                            add(
                                column {
                                    add(text(r.patient.pseudoId, 16f, Gov.INK, bold = true))
                                    add(
                                        text(
                                            "${r.patient.ageBand.ifBlank { "—" }} · ${r.patient.villageCode.ifBlank { "—" }}",
                                            12.5f, Gov.INK_MUTED,
                                        ),
                                        top = 2,
                                    )
                                },
                                weight = 1f,
                            )
                            add(tierBadge(r.decision.tier), matchWidth = false)
                        },
                        bottom = 8,
                    )
                    add(dataRow("Heart rate", "${r.inputs.meanHr.roundToInt()} bpm"))
                    addView(hairline())
                    add(dataRow("Signal quality", "${r.quality.percent}%"))
                    addView(hairline())
                    add(
                        dataRow(
                            "Referral",
                            r.referralState.wire.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            if (r.referralState == ReferralState.CLOSED) Gov.GREEN else Gov.ORANGE,
                            bold = true,
                        ),
                    )
                    add(
                        row {
                            add(
                                secondaryButton("Mark contacted") {
                                    r.referralState = ReferralState.PATIENT_CONTACTED
                                    render()
                                },
                                weight = 1f, right = 5,
                            )
                            add(
                                secondaryButton("Close") {
                                    r.referralState = ReferralState.CLOSED
                                    render()
                                },
                                weight = 1f, left = 5,
                            )
                        },
                        top = 10,
                    )
                },
                bottom = 10,
            )
        }

        add(
            text(
                "Referral state records process, not findings. Only a clinician records what was found.",
                12.5f, Gov.INK_FAINT,
            ),
            top = 4,
        )
    }

    // ---- District view (spec 10, 17) --------------------------------------

    private fun districtScreen(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        pageTitle("District overview", "Aggregated — no identifying information", back = Screen.HOME)

        val villages = controller.byVillage()
        val scored = controller.scoredCount()
        val flagged = controller.referralQueue().size

        add(
            card {
                add(sectionLabel("This device"))
                add(dataRow("Patients screened", "${controller.patientCount()}"))
                addView(hairline())
                add(dataRow("Scored recordings", "$scored"))
                addView(hairline())
                add(
                    dataRow(
                        "Referral rate",
                        if (scored == 0) "—" else "${(flagged * 100.0 / scored).roundToInt()}%",
                    ),
                )
                addView(hairline())
                add(
                    dataRow(
                        "Repeat rate",
                        if (controller.session.isEmpty()) "—" else
                            "${(controller.count(Tier.RETAKE) * 100.0 / controller.session.size).roundToInt()}%",
                    ),
                )
            },
            bottom = 12,
        )

        if (villages.isEmpty()) {
            add(card { add(text("No village-coded screenings yet.", 14f, Gov.INK_MUTED)) })
        } else {
            add(sectionLabel("By locality"))
            villages.forEach { (code, rs) ->
                val f = rs.count { it.referrable }
                add(
                    card {
                        add(
                            row {
                                add(text(code, 15.5f, Gov.INK, bold = true), weight = 1f)
                                add(text("${rs.size} screened", 13f, Gov.INK_MUTED), matchWidth = false)
                            },
                            bottom = 8,
                        )
                        addView(
                            meterRow(
                                "Referrals",
                                if (rs.isEmpty()) 0.0 else f.toDouble() / rs.size,
                                if (f > 0) Gov.ORANGE else Gov.GREEN,
                            ),
                        )
                    },
                    bottom = 10,
                )
            }
        }

        add(
            text(
                "Counts come only from screenings recorded on this device in this session. " +
                    "District-wide figures come from the sync service, not from here.",
                12.5f, Gov.INK_FAINT,
            ),
            top = 4,
        )
    }

    // ---- Assistant (spec 15) ----------------------------------------------

    private fun assistantScreen(): Unit = with(body) {
        setPadding(dp(16), dp(16), dp(16), dp(24))
        pageTitle("Help", "Answers a fixed set of questions about this app", back = Screen.HOME)

        val answerBox = column {}
        val input = EditText(this@MainActivity).apply {
            hint = "Ask a question"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Gov.INK)
            setHintTextColor(Gov.INK_FAINT)
            background = roundedFill(Gov.SURFACE, dp(4).toFloat(), dp(1), Gov.HAIRLINE)
            setPadding(dp(12), dp(13), dp(12), dp(13))
        }

        fun ask(q: String) {
            val r = controller.current
            val ctx = AssistantContext(
                screenedToday = controller.scoredCount(),
                referralsPending = controller.pendingReferrals(),
                referralsOverdue = 0,
                lastTier = r?.decision?.tier,
                lastReasons = (r?.reasons ?: emptyList()) + (r?.risk?.reasons ?: emptyList()),
                priorVisits = r?.let { controller.historyFor(it.patient.pseudoId).totalScreenings - 1 } ?: 0,
                lastQualityPercent = r?.quality?.percent,
            )
            val a = Assistant.answer(q, ctx)
            answerBox.removeAllViews()
            answerBox.addView(
                card {
                    add(text(q, 14f, Gov.INK_MUTED, bold = true), bottom = 8)
                    add(text(a.text, 15f, Gov.INK))
                    if (a.deferredToClinician) {
                        add(
                            text("REFERRED TO CLINICIAN", 10.5f, Gov.RED, bold = true, spacing = 0.06f).apply {
                                background = roundedFill(Gov.RED_FILL, dp(3).toFloat())
                                setPadding(dp(8), dp(4), dp(8), dp(4))
                            },
                            top = 10,
                        )
                    }
                },
            )
        }

        add(input, bottom = 8)
        add(primaryButton("Ask") { ask(input.text.toString()) }, bottom = 14)
        add(answerBox, bottom = 14)

        add(sectionLabel("Common questions"))
        Assistant.suggestions().forEach { s ->
            add(
                card(pad = 13) {
                    add(text(s, 14.5f, Gov.NAVY))
                    setOnClickListener { input.setText(s); ask(s) }
                },
                bottom = 8,
            )
        }

        add(
            card {
                add(sectionLabel("What this assistant will not do"))
                listOf(
                    "Name a condition or give a diagnosis",
                    "Recommend or adjust any medicine",
                    "Override what a clinician decided",
                    "Invent information that is not in the record",
                ).forEach { add(bullet(it), top = 3) }
                add(
                    text(
                        "Every answer comes from a fixed, reviewable table — nothing here is generated.",
                        12.5f, Gov.INK_FAINT,
                    ),
                    top = 8,
                )
            },
            top = 6,
        )
    }

    /** One completed screening as the JSON the encrypted store holds. */
    private fun recordJson(r: ScreeningResult): org.json.JSONObject = org.json.JSONObject().apply {
        put("recordId", java.util.UUID.randomUUID().toString())
        put("schemaVersion", com.arogyax.data.ScreeningRecord.SCHEMA_VERSION)
        put("patientPseudoId", r.patient.pseudoId)
        put("capturedAt", r.capturedAt.toString())
        put("ageBand", r.patient.ageBand)
        put("villageCode", r.patient.villageCode)
        put("sex", r.patient.sex ?: org.json.JSONObject.NULL)
        put("tier", r.decision.tier.name)
        put("decidedBy", r.decision.decidedBy.wire)
        put("sqiScore", r.inputs.sqiScore)
        put("meanHr", r.inputs.meanHr)
        put("rrIntervalCount", r.inputs.rrIntervalCount)
        put("rrIrregularityScore", r.inputs.rrIrregularityScore)
        put("modelVersion", com.arogyax.core.Policy.versionFor(r.decision.decidedBy))
        // The waveform is deliberately NOT stored: 7500 doubles per screening
        // would dominate the file, and nothing downstream reads it back.
    }

    /**
     * Speaks a result: the tier clip, the "screening not a diagnosis" line,
     * then one clip sequence per [ScreeningResult.reasons] entry that the
     * DRAFT audio manifest actually covers.
     *
     * Silently skips a [com.arogyax.core.Reason] with no manifest entry (the
     * risk-engine keys added after ticket 015's audio generation, `risk_*`,
     * have no spoken form yet) rather than crashing the sequence partway
     * through - a result screen must remain usable even when its narration
     * is incomplete.
     */
    private fun speakResult(r: ScreeningResult, onDone: () -> Unit = {}) {
        val audio = explanationAudio
        val clips = mutableListOf(TierAudioClips.assetPathFor(r.decision.tier))
        if (r.decision.tier != Tier.RETAKE) clips.add(TierAudioClips.SUPPORTING_LINE_ASSET)

        if (audio != null) {
            for (reason in r.reasons) {
                try {
                    clips.addAll(audio.clipsFor(reason.key, reason.values))
                } catch (_: IllegalArgumentException) {
                    // No manifest entry for this key, or a value it needed was
                    // missing - narrate what we can, not nothing at all.
                }
            }
        }
        sequencePlayer.playSequence(clips, onComplete = onDone)
    }

    // ---- Small helpers ----------------------------------------------------

    private fun fmt(v: Double) = String.format("%.2f", v)

    private fun tierOf(s: String): Tier = runCatching { Tier.valueOf(s) }.getOrDefault(Tier.RETAKE)

    private fun priorityLabel(p: ScreeningPriority) = when (p) {
        ScreeningPriority.ROUTINE -> "Routine"
        ScreeningPriority.REPEAT -> "Repeat measurement"
        ScreeningPriority.REFERRAL -> "PHC referral"
        ScreeningPriority.PRIORITY_REVIEW -> "Priority review"
    }

    private fun trajectoryLabel(t: RiskTrajectory) = when (t) {
        RiskTrajectory.INSUFFICIENT_DATA -> "not enough visits to say"
        RiskTrajectory.STABLE -> "stable"
        RiskTrajectory.IMPROVING -> "flagged less often recently"
        RiskTrajectory.FLUCTUATING -> "varies between visits"
        RiskTrajectory.INCREASING -> "flagged more often recently"
        RiskTrajectory.REPEATEDLY_SUSPICIOUS -> "flagged on several recent visits"
    }

    private fun decidedByLabel(wire: String) = when (wire) {
        "gate" -> "Signal check"
        "rules" -> "Beat-timing rules"
        "cnn" -> "On-device analysis"
        "rules+cnn" -> "Both detectors"
        "history" -> "Earlier visits"
        else -> wire
    }

    private fun tamilFor(t: Tier) = when (t) {
        Tier.RED -> "இன்றே ஆரம்ப சுகாதார நிலையத்திற்குச் செல்லவும் — 4 மணி நேரத்திற்குள்."
        Tier.ORANGE -> "24 மணி நேரத்திற்குள் ஆரம்ப சுகாதார நிலையத்தில் பரிசோதனை செய்யவும்."
        Tier.YELLOW -> "இரண்டு நாட்களுக்குள் ஆரம்ப சுகாதார நிலையத்தில் பரிசோதனை செய்யவும்."
        Tier.GREEN -> "இன்று இதயத் துடிப்பில் சிக்கல் எதுவும் இல்லை."
        Tier.RETAKE -> "சமிக்ஞை தெளிவாக இல்லை. மீண்டும் பரிசோதிக்கவும்."
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ble.close()
        sequencePlayer.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQ_BLE = 101

        /** DRAFT Tamil, ticket 011 - shown with a DRAFT chip, never as reviewed copy. */
        const val TAMIL_ELECTRODE_HINT = "மின்முனைகளை சரியாக பொருத்தவும்."
    }
}
