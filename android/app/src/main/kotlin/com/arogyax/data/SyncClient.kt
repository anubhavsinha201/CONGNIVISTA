package com.arogyax.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Per-record outcome of an upload. See contracts/sync.md section 5. */
enum class UploadStatus {
    /** Stored server-side. Only this marks a record synced. */
    ACCEPTED,

    /** Malformed. Retrying cannot fix it, so it leaves the queue. */
    REJECTED,

    /** Server-side transient. Stays pending, backs off. */
    RETRYABLE,
}

data class UploadResult(val recordId: String, val status: UploadStatus, val code: String? = null)

data class AckPage(val acks: List<ReferralAck>, val cursor: OffsetDateTime?) {
    companion object {
        val EMPTY = AckPage(emptyList(), null)
    }
}

/**
 * Raised for transport-level failure - no radio, DNS, TLS, timeout, 5xx.
 * Distinct from a per-record REJECTED: this says nothing about the records
 * themselves, so every record in the batch stays pending.
 */
class SyncTransportException(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * Raised when the device token is missing, revoked, or attributed to
 * another worker. Not retryable by backing off - it needs re-provisioning.
 */
class SyncAuthException(message: String) : Exception(message)

/**
 * Transport for the sync service. Kept as an interface so [SyncEngine] can
 * be tested against a fake with no HTTP, no server, no timing - the engine
 * holds the retry policy and the state machine (the parts worth testing
 * exhaustively), this holds only the wire format.
 *
 * Port of app/lib/data/sync_client.dart's SyncClient - keep the two in sync.
 */
interface SyncClient {
    /** At most 25 records, oldest first. One [UploadResult] per record. */
    fun uploadBatch(records: List<ScreeningRecord>): List<UploadResult>

    /** Referral state changes since [since]. Returns the acks and the cursor to pass as the next [since]. */
    fun fetchAcks(since: OffsetDateTime? = null): AckPage
}

/**
 * Real HTTP implementation, against `contracts/sync.md`'s wire protocol
 * exactly (verified directly against the running server this session, not
 * assumed from the contract doc alone - see ticket 019's sync section).
 *
 * Uses [HttpURLConnection], not a newer client - deliberately. This
 * product's target device is a budget Android phone (docs/PRODUCT.md), and
 * `java.net.http.HttpClient` (Java 11+) isn't available on Android below
 * API 34; `HttpURLConnection` has been part of every Android API level since
 * 1. Zero new dependency either way - this is the actually-more-compatible
 * choice for this specific deployment target, not just the smaller one.
 */
class HttpSyncClient(
    private val baseUrl: String,
    private val deviceToken: String,
    /**
     * Short on purpose. The realistic failure is not a slow server, it is a
     * tower that completes a TCP handshake and then goes nowhere as the
     * worker's bus moves. Hanging for 30s on that burns the whole window of
     * coverage the flush had to work with.
     */
    private val timeoutMs: Int = 12_000,
) : SyncClient {

    override fun uploadBatch(records: List<ScreeningRecord>): List<UploadResult> {
        if (records.isEmpty()) return emptyList()

        val body = JSONObject()
        val arr = JSONArray()
        for (r in records) arr.put(r.toSyncJson())
        body.put("records", arr)

        val (code, text) = send("${baseUrl.trimEnd('/')}/v1/records:batch", "POST", body.toString())

        if (code == 401 || code == 403) throw SyncAuthException("device token rejected ($code)")
        if (code != 200) throw SyncTransportException("batch upload failed", statusCode = code)

        val parsed = JSONObject(text)
        val results = parsed.optJSONArray("results") ?: JSONArray()
        return List(results.length()) { i ->
            val r = results.getJSONObject(i)
            val status = when (r.optString("status")) {
                "accepted" -> UploadStatus.ACCEPTED
                "rejected" -> UploadStatus.REJECTED
                else -> UploadStatus.RETRYABLE
            }
            UploadResult(r.getString("recordId"), status, r.optString("code", null))
        }
    }

    override fun fetchAcks(since: OffsetDateTime?): AckPage {
        // whvId is deliberately NOT a parameter - the server derives it from
        // the token, so a device cannot ask for another worker's referrals
        // by editing a query string.
        var url = "${baseUrl.trimEnd('/')}/v1/acks"
        if (since != null) {
            val encoded = URLEncoder.encode(since.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "UTF-8")
            url += "?since=$encoded"
        }

        val (code, text) = send(url, "GET", null)

        if (code == 401 || code == 403) throw SyncAuthException("device token rejected ($code)")
        if (code != 200) throw SyncTransportException("ack fetch failed", statusCode = code)

        val parsed = JSONObject(text)
        val acksJson = parsed.optJSONArray("acks") ?: JSONArray()
        val acks = List(acksJson.length()) { i -> ReferralAck.fromJson(acksJson.getJSONObject(i)) }
        val cursor = if (parsed.isNull("cursor") || !parsed.has("cursor")) {
            null
        } else {
            OffsetDateTime.parse(parsed.getString("cursor"))
        }
        return AckPage(acks, cursor)
    }

    /**
     * Everything below HTTP - socket, DNS, TLS, timeout - is one thing to
     * the caller: the network did not work. The distinction that matters is
     * transport-vs-record, and that is preserved (auth is checked by the
     * caller on the returned status code, same as the Dart side).
     */
    private fun send(url: String, method: String, body: String?): Pair<Int, String> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw SyncTransportException("$e")
        }
        return try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Authorization", "Bearer $deviceToken")
            conn.setRequestProperty("Content-Type", "application/json")

            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { out: OutputStream -> out.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } catch (e: Exception) {
            throw SyncTransportException("$e")
        } finally {
            conn.disconnect()
        }
    }
}
