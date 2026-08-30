package com.arogyax.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The on-device patient store, encrypted at rest.
 *
 * ## Why this and not SQLCipher
 *
 * The Dart reference used SQLCipher. Its Android equivalent is a ~7 MB native
 * dependency, and this module deliberately builds with no network (see CLAUDE.md
 * Gotchas). AES-256-GCM from `javax.crypto`, with the key held in the Android
 * Keystore, is in the platform — no download, and the key is hardware-backed on
 * any device with a TEE or StrongBox.
 *
 * What is given up is real and worth stating: SQLCipher encrypts a queryable
 * database, this encrypts one blob that is read and written whole. At a health
 * worker's daily volume — tens of screenings, not millions — that is the right
 * trade. It stops being right when the file no longer fits comfortably in
 * memory, which is the point to move to SQLCipher rather than paginate this.
 *
 * ## What the encryption is actually for
 *
 * Records hold no name, phone or Aadhaar — only a salted `patientPseudoId`
 * (non-negotiable 5). So this is not the last line of defence for identity; it
 * is protection for a lost or stolen handset carrying a village's worth of
 * screening results and referral states, which is still sensitive health
 * information about identifiable households even without a name attached.
 *
 * ## Properties
 *
 * - **AES-256-GCM**, so tampering is detected, not just hidden. A modified file
 *   fails to decrypt rather than yielding altered records.
 * - **A fresh 12-byte IV per write**, stored in front of the ciphertext. Reusing
 *   an IV under GCM is catastrophic, so it is never derived or reused.
 * - **The key never leaves the Keystore.** It cannot be exported, only used.
 * - **Atomic writes.** Encrypt to a temp file and rename, so a process death
 *   mid-write leaves the previous good file rather than a truncated one that
 *   would fail authentication and lose every record.
 */
class EncryptedStore(
    context: Context,
    fileName: String = "screenings.enc",
) {

    private val file = File(context.filesDir, fileName)
    private val tmp = File(context.filesDir, "$fileName.tmp")

    /** Records, newest first. Held in memory; [save] writes the whole set. */
    private val records = mutableListOf<JSONObject>()

    val size: Int get() = records.size
    val exists: Boolean get() = file.exists()
    val sizeOnDiskBytes: Long get() = if (file.exists()) file.length() else 0L

    /**
     * Loads and decrypts.
     *
     * @return null on success, or a human-readable reason on failure. A failure
     *   is deliberately not silent: an unreadable store means either tampering
     *   or a lost key, and quietly starting empty would look identical to a
     *   fresh install while a patient's history sat unreadable on disk.
     */
    fun load(): String? {
        records.clear()
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            if (blob.size <= IV_BYTES) return "Store file is truncated"
            val iv = blob.copyOfRange(0, IV_BYTES)
            val body = blob.copyOfRange(IV_BYTES, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val json = String(cipher.doFinal(body), Charsets.UTF_8)

            val arr = JSONArray(json)
            for (i in 0 until arr.length()) records.add(arr.getJSONObject(i))
            null
        } catch (e: Exception) {
            // AEADBadTagException lands here too - that is the tamper signal.
            "Could not decrypt the patient store (${e.javaClass.simpleName})"
        }
    }

    /** Encrypts and writes atomically. Returns null on success. */
    fun save(): String? = try {
        val arr = JSONArray()
        records.forEach { arr.put(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // Never reuse a GCM IV. Letting the provider generate it per init is
        // the safest way to guarantee that.
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        val body = cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8))

        tmp.writeBytes(iv + body)
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems; fall back to a copy so
            // a failure here does not silently drop the save.
            file.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        null
    } catch (e: Exception) {
        "Could not save the patient store (${e.javaClass.simpleName})"
    }

    fun add(record: JSONObject): String? {
        records.add(0, record)
        return save()
    }

    fun all(): List<JSONObject> = records.toList()

    fun forPatient(pseudoId: String): List<JSONObject> =
        records.filter { it.optString("patientPseudoId") == pseudoId }

    /** Wipes the store and its file. Irreversible — the key stays. */
    fun clear(): String? {
        records.clear()
        file.delete()
        return null
    }

    /**
     * The AES key, created on first use and thereafter fetched from the Keystore.
     *
     * `setUserAuthenticationRequired` is deliberately NOT set: a health worker
     * screens in a doorway with gloves on, and a store that demands a fingerprint
     * before each save is a store that gets worked around. The threat model here
     * is a lost handset, which device lock already addresses.
     */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "arogyax_record_store_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
