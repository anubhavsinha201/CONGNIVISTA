package com.arogyax.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.arogyax.core.Tier

/**
 * The design system, as plain framework Views.
 *
 * No Compose, no AppCompat, no Material artifact — the module's only dependency
 * is still `org.json`. That is deliberate twice over: it matches the discipline
 * the signal layer already follows (CLAUDE.md: "deliberately depend on nothing
 * beyond dart:math and dart:typed_data ... keep it that way"), and it means the
 * app builds and runs on a field laptop with no network, which is the same
 * property the product itself claims.
 *
 * The visual language is a government service form rather than a consumer health
 * app: a fixed department band, ruled cards with square corners, flat fills, and
 * no decoration that could be read as a status signal.
 *
 * **Colour is a tier and nothing else is allowed to be.** RED/ORANGE/YELLOW/
 * GREEN carry clinical meaning, so all chrome stays navy, grey and white, and
 * every tier is carried by a word as well as a colour — sunlight and
 * colour-blindness both defeat colour alone.
 */
object Gov {

    // ---- Chrome ----------------------------------------------------------
    val NAVY = Color.parseColor("#0B2E5C")
    val NAVY_DARK = Color.parseColor("#061B38")
    val NAVY_LIGHT = Color.parseColor("#1B4A88")
    val SAFFRON = Color.parseColor("#FF9933")
    val INDIA_GREEN = Color.parseColor("#138808")

    val INK = Color.parseColor("#14181F")
    val INK_MUTED = Color.parseColor("#5A6472")
    val INK_FAINT = Color.parseColor("#8A94A3")
    val HAIRLINE = Color.parseColor("#D8DEE7")
    val SURFACE = Color.WHITE
    val CANVAS = Color.parseColor("#F1F4F8")
    val SUNKEN = Color.parseColor("#E6EBF2")

    // ---- Tier palette ----------------------------------------------------
    val RED = Color.parseColor("#B3261E")
    val RED_FILL = Color.parseColor("#FCEAE8")
    val ORANGE = Color.parseColor("#B55400")
    val ORANGE_FILL = Color.parseColor("#FDF0E3")
    val YELLOW = Color.parseColor("#8A6A00")
    val YELLOW_FILL = Color.parseColor("#FBF3D9")
    val GREEN = Color.parseColor("#1B6E2E")
    val GREEN_FILL = Color.parseColor("#E7F4EA")
    val GREY = Color.parseColor("#4A5568")
    val GREY_FILL = Color.parseColor("#ECEFF3")

    fun ink(t: Tier) = when (t) {
        Tier.RED -> RED; Tier.ORANGE -> ORANGE; Tier.YELLOW -> YELLOW
        Tier.GREEN -> GREEN; Tier.RETAKE -> GREY
    }

    fun fill(t: Tier) = when (t) {
        Tier.RED -> RED_FILL; Tier.ORANGE -> ORANGE_FILL; Tier.YELLOW -> YELLOW_FILL
        Tier.GREEN -> GREEN_FILL; Tier.RETAKE -> GREY_FILL
    }

    /** The worker-facing label. Never a condition name — non-negotiable 1. */
    fun label(t: Tier) = when (t) {
        Tier.RED -> "PRIORITY REFERRAL"
        Tier.ORANGE -> "REFERRAL"
        Tier.YELLOW -> "REFERRAL"
        Tier.GREEN -> "ROUTINE"
        Tier.RETAKE -> "REPEAT CAPTURE"
    }

    fun timeframe(t: Tier) = when (t) {
        Tier.RED -> "Refer today — within 4 hours"
        Tier.ORANGE -> "Refer within 24 hours"
        Tier.YELLOW -> "Refer within 48 hours"
        Tier.GREEN -> "No rhythm concern today"
        Tier.RETAKE -> "Not a result — capture again"
    }
}

// ---- Unit helpers --------------------------------------------------------

fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
).toInt()

fun View.dp(v: Int) = context.dp(v)

/** Minimum comfortable target for a gloved hand in a doorway. */
const val TOUCH_TARGET_DP = 56

// ---- Layout builders -----------------------------------------------------

fun Context.column(
    pad: Int = 0,
    bg: Int? = null,
    fillHeight: Boolean = false,
    build: LinearLayout.() -> Unit = {},
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (pad != 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    bg?.let { setBackgroundColor(it) }
    // fillHeight matters for any column hosting a weighted child: weight divides
    // LEFTOVER space, and a WRAP_CONTENT column has none to give.
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (fillHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    build()
}

fun Context.row(
    pad: Int = 0,
    build: LinearLayout.() -> Unit = {},
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (pad != 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    build()
}

/**
 * Adds [v] with optional weight and margins, and returns it.
 *
 * **A view that has already set its own LayoutParams keeps its width and height.**
 * The first version of this helper always built fresh params, which silently
 * destroyed every explicit size in the app - the tricolour stripes, the meters
 * and the ECG trace all collapsed to zero height, because a bare View measures
 * to nothing under WRAP_CONTENT. Only margins and weight are applied on top.
 */
fun <T : View> LinearLayout.add(
    v: T,
    weight: Float = 0f,
    top: Int = 0,
    bottom: Int = 0,
    left: Int = 0,
    right: Int = 0,
    matchWidth: Boolean = true,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
): T {
    val horizontal = orientation == LinearLayout.HORIZONTAL
    val existing = v.layoutParams as? LinearLayout.LayoutParams

    val w = when {
        existing != null -> existing.width
        weight > 0f && horizontal -> 0
        matchWidth -> ViewGroup.LayoutParams.MATCH_PARENT
        else -> ViewGroup.LayoutParams.WRAP_CONTENT
    }
    val h = when {
        existing != null -> existing.height
        weight > 0f && !horizontal -> 0
        else -> height
    }

    val lp = LinearLayout.LayoutParams(w, h, weight)
    lp.setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
    addView(v, lp)
    return v
}

/** A fixed-height spacer. Clearer than an empty View with a magic height. */
fun Context.gap(h: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
}

// ---- Text ----------------------------------------------------------------

fun Context.text(
    s: CharSequence,
    size: Float = 15f,
    color: Int = Gov.INK,
    bold: Boolean = false,
    caps: Boolean = false,
    spacing: Float = 0f,
): TextView = TextView(this).apply {
    text = if (caps) s.toString().uppercase() else s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (spacing != 0f) letterSpacing = spacing
    setLineSpacing(dp(3).toFloat(), 1f)
}

/** Small-caps section heading, the way a printed form labels a block. */
fun Context.sectionLabel(s: String): TextView =
    text(s, size = 11.5f, color = Gov.INK_FAINT, bold = true, caps = true, spacing = 0.09f)

// ---- Surfaces ------------------------------------------------------------

fun roundedFill(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** A bordered white block. Government forms use rules, not shadows. */
fun Context.card(pad: Int = 16, build: LinearLayout.() -> Unit = {}): LinearLayout =
    column(pad = pad) {
        background = roundedFill(Gov.SURFACE, dp(4).toFloat(), dp(1), Gov.HAIRLINE)
        build()
    }

/** `Label ................ value`, the densest honest way to show a field. */
fun Context.dataRow(label: String, value: String, valueColor: Int = Gov.INK, bold: Boolean = false): LinearLayout =
    row {
        setPadding(0, dp(7), 0, dp(7))
        add(text(label, 14f, Gov.INK_MUTED), weight = 1f)
        add(
            text(value, 14f, valueColor, bold = bold).apply { gravity = Gravity.END },
            matchWidth = false,
        )
    }

fun Context.hairline(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    setBackgroundColor(Gov.HAIRLINE)
}

/** Tier badge. Always carries the word — colour alone is not a label. */
fun Context.tierBadge(t: Tier, large: Boolean = false): TextView =
    text(Gov.label(t), if (large) 16f else 11.5f, Gov.ink(t), bold = true, spacing = 0.06f).apply {
        background = roundedFill(Gov.fill(t), dp(3).toFloat())
        setPadding(dp(if (large) 14 else 8), dp(if (large) 8 else 4), dp(if (large) 14 else 8), dp(if (large) 8 else 4))
    }

/** Full-width primary action, sized for a gloved thumb. */
fun Context.primaryButton(label: String, color: Int = Gov.NAVY, enabled: Boolean = true, onTap: () -> Unit): TextView =
    text(label, 16f, if (enabled) Color.WHITE else Gov.INK_FAINT, bold = true).apply {
        gravity = Gravity.CENTER
        background = roundedFill(if (enabled) color else Gov.SUNKEN, dp(4).toFloat())
        minimumHeight = dp(TOUCH_TARGET_DP)
        setPadding(dp(20), dp(17), dp(20), dp(17))
        isEnabled = enabled
        if (enabled) setOnClickListener { onTap() }
    }

fun Context.secondaryButton(label: String, onTap: () -> Unit): TextView =
    text(label, 16f, Gov.NAVY, bold = true).apply {
        gravity = Gravity.CENTER
        background = roundedFill(Gov.SURFACE, dp(4).toFloat(), dp(2), Gov.NAVY)
        minimumHeight = dp(TOUCH_TARGET_DP)
        setPadding(dp(20), dp(17), dp(20), dp(17))
        setOnClickListener { onTap() }
    }

/**
 * The standing claim boundary. Shown wherever a result is.
 *
 * Non-negotiable 1 is enforced in the decision layer, but a worker reads a
 * screen, not a policy file, so the boundary is restated where the result is
 * rather than buried in an about page.
 */
fun Context.disclaimer(): LinearLayout = row(pad = 12) {
    background = roundedFill(Gov.SUNKEN, dp(4).toFloat())
    gravity = Gravity.TOP
    add(
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(34))
            background = roundedFill(Gov.SAFFRON, dp(2).toFloat())
        },
        matchWidth = false,
    )
    add(
        text(
            "This is a screening check only, not a diagnosis. Only a doctor decides.",
            13f, Gov.INK_MUTED,
        ),
        weight = 1f, left = 10,
    )
}

/**
 * Marks anything drawn from `tamil_strings_DRAFT.json` — a machine draft no
 * native Tamil speaker or clinician has reviewed (ticket 011). It has to look
 * provisional everywhere it appears.
 */
fun Context.draftChip(): TextView =
    text("DRAFT TAMIL · NOT REVIEWED", 10.5f, Gov.YELLOW, bold = true, spacing = 0.05f).apply {
        background = roundedFill(Gov.YELLOW_FILL, dp(3).toFloat())
        setPadding(dp(7), dp(3), dp(7), dp(3))
    }
