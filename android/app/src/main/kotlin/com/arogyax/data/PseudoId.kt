package com.arogyax.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives `patientPseudoId` from an MTM patient identifier.
 *
 * The salt is deployment-wide (one per PHC/district, not per device) so the
 * PHC can re-identify from its own MTM roll - a per-device random salt would
 * be stronger privacy but would make the same patient screened by two
 * workers look like two patients. This is pseudonymisation, not
 * anonymisation: the salt is only as secret as the APK. The raw MTM
 * identifier is hashed at the call site and never stored, logged, or leaves
 * the device (CLAUDE.md non-negotiable 5).
 *
 * Port of app/lib/data/pseudo_id.dart's PseudoId - keep the two in sync.
 */
object PseudoId {
    /** Hex characters kept from the digest. 16 hex chars = 64 bits. */
    const val LENGTH = 16

    /**
     * HMAC-SHA256 over the normalised identifier, keyed by the deployment
     * salt. HMAC rather than sha256(salt + id): plain concatenation is
     * length-extendable and invites the salt being appended instead of
     * prepended by whoever writes the PHC-side script.
     */
    fun derive(mtmPatientId: String, deploymentSalt: String): String {
        require(deploymentSalt.isNotEmpty()) {
            "refusing to derive a pseudo-ID with an empty salt"
        }
        val normalised = normalise(mtmPatientId)
        require(normalised.isNotEmpty()) { "empty after normalisation" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deploymentSalt.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(normalised.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, LENGTH)
    }

    /**
     * Uppercase, strip everything that is not alphanumeric - so
     * `TN-1234 5678`, `tn12345678`, and `TN 1234-5678` all collide to the
     * same pseudo-ID.
     */
    fun normalise(raw: String): String = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
}
