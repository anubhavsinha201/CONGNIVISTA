package com.arogyax.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** Where a capture is in the connect → stream → assemble sequence. */
enum class BleState { IDLE, SCANNING, CONNECTING, READY, STREAMING, COMPLETE, FAILED }

/**
 * Live ECG from the ESP32 sensor unit over BLE. Ticket 014.
 *
 * The wire format and every UUID come from `contracts/ble.md`; the frame
 * decoding and the sequence-gap rule live in [EcgFrameParser], which has no
 * Android types in it and is unit-tested. This class is only the radio plumbing
 * around that.
 *
 * ## Two things worth knowing before changing this
 *
 * 1. **The unit boots idle.** It advertises immediately but samples nothing until
 *    a start opcode is written to the control characteristic (`ble.md` §5). That
 *    is a battery decision, not an oversight — so a connection alone produces no
 *    data, and forgetting [START_CAPTURE] looks exactly like broken hardware.
 * 2. **A dropped frame invalidates the window.** Enforced in [EcgFrameParser];
 *    this class surfaces it through [EcgFrameParser.gapDetected] so the capture
 *    is refused rather than scored (non-negotiable 3).
 *
 * Every callback is delivered on the main thread, because they drive the UI and
 * the BLE stack calls back on a binder thread.
 */
class BleEcgSource(
    private val context: Context,
    private val windowSamples: Int = 7500,
) {

    val parser = EcgFrameParser(windowSamples)

    var state: BleState = BleState.IDLE
        private set

    /** Latest status frame from the unit, or null before the first one arrives. */
    var status: SensorStatus? = null
        private set

    // ---- Callbacks, all on the main thread ---------------------------------

    /** Samples from one frame, as they arrive, for the live trace. */
    var onSamples: (DoubleArray) -> Unit = {}
    var onState: (BleState, String?) -> Unit = { _, _ -> }
    var onStatus: (SensorStatus) -> Unit = {}
    var onProgress: (Double) -> Unit = {}

    /** The assembled window, once [windowSamples] have arrived. */
    var onWindow: (DoubleArray, Boolean) -> Unit = { _, _ -> }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var controlChar: BluetoothGattCharacteristic? = null

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    val bluetoothReady: Boolean get() = adapter?.isEnabled == true

    private fun post(s: BleState, message: String? = null) = main.post {
        state = s
        onState(s, message)
    }

    // ---- Finding the unit ---------------------------------------------------

    /** Names seen during the last scan, for the failure message. */
    private val seen = linkedSetOf<String>()

    /**
     * Starts a capture.
     *
     * Three ways in, tried in order, because each fails in a different real
     * situation:
     *
     * 1. **A bonded device, connected directly by address.** A BLE peripheral
     *    stops advertising while it holds a connection, and it only has one
     *    slot. If the phone has already paired with the unit — or the system
     *    quietly reconnected to it — no scan will ever see it again, and
     *    scanning harder does not help. Connecting by address works anyway.
     * 2. **A scan filtered on the service UUID.** The correct path for a fresh
     *    unit that nobody has paired with.
     * 3. **An unfiltered scan matched on the advertised name.** A 128-bit
     *    service UUID costs 18 of the 31 advertising bytes, so on some ESP32
     *    builds it lands in the scan response — or is dropped entirely — and a
     *    UUID filter then matches a device that is plainly there.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(timeoutMs: Long = 15_000) {
        val a = adapter
        if (a == null) {
            post(BleState.FAILED, "This phone has no Bluetooth adapter")
            return
        }
        if (!a.isEnabled) {
            post(BleState.FAILED, "Bluetooth is off - turn it on and try again")
            return
        }

        parser.reset()
        seen.clear()
        post(BleState.SCANNING)

        // 1. Already bonded? Go straight there - it may not be advertising.
        val bonded = try {
            a.bondedDevices?.firstOrNull { it.name?.startsWith(NAME_PREFIX) == true }
        } catch (e: SecurityException) {
            post(BleState.FAILED, "Bluetooth permission not granted")
            return
        }
        if (bonded != null) {
            connect(bonded)
            // A bonded unit that is switched off never calls back at all, so
            // the attempt still needs a deadline.
            main.postDelayed({
                if (state == BleState.CONNECTING) {
                    post(BleState.FAILED, "Paired unit did not respond. Power-cycle it and retry.")
                    close()
                }
            }, timeoutMs)
            return
        }

        scan(filtered = true, timeoutMs = timeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun scan(filtered: Boolean, timeoutMs: Long) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            post(BleState.FAILED, "Bluetooth scanner unavailable")
            return
        }

        val filters = if (filtered) {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            post(BleState.FAILED, "Bluetooth permission not granted")
            return
        }

        main.postDelayed({
            if (!scanning) return@postDelayed
            stopScan()
            if (filtered) {
                // The unit may be advertising without its service UUID in the
                // advertising packet. Look again, matching on the name.
                scan(filtered = false, timeoutMs = timeoutMs)
            } else {
                val nearby = if (seen.isEmpty()) {
                    "No BLE devices were visible at all."
                } else {
                    "Nearby: " + seen.take(6).joinToString(", ")
                }
                post(
                    BleState.FAILED,
                    "No sensor unit found. If it was connected a moment ago, " +
                        "power-cycle it - a BLE unit stops advertising while it " +
                        "still thinks something is attached. $nearby",
                )
            }
        }, timeoutMs)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.device.name ?: result.scanRecord?.deviceName
            } catch (_: SecurityException) {
                null
            }
            name?.let { seen.add(it) }

            val hasUuid = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (hasUuid || name?.startsWith(NAME_PREFIX) == true) {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            post(BleState.FAILED, "Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-scan. Nothing to clean up that matters.
        }
    }

    // ---- Connect and stream -------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        post(BleState.CONNECTING)
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            post(BleState.FAILED, "Bluetooth permission not granted")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // 56 bytes exceeds the 23-byte default ATT MTU, so a larger one
                // must be negotiated or every ECG frame arrives truncated
                // (`ble.md` §6). Service discovery waits for the MTU result.
                if (!g.requestMtu(DESIRED_MTU)) g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (state == BleState.STREAMING && !parser.complete) {
                    post(BleState.FAILED, "Sensor disconnected during the recording")
                }
                // The peripheral has confirmed the link is down, so the client
                // interface can now be released without stranding it.
                main.post { releaseGatt() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) {
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                post(BleState.FAILED, "Device is not an ArogyaX sensor unit")
                close()
                return
            }
            controlChar = service.getCharacteristic(CONTROL_UUID)

            // Notifications are enabled one at a time: the GATT queue accepts a
            // single outstanding descriptor write, and firing both immediately
            // silently drops the second.
            val ecg = service.getCharacteristic(ECG_UUID)
            if (ecg == null) {
                post(BleState.FAILED, "Sensor unit is missing the ECG characteristic")
                close()
                return
            }
            enableNotifications(g, ecg)
            post(BleState.READY)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            when (d.characteristic.uuid) {
                ECG_UUID -> {
                    // ECG is subscribed; now the status channel, then start.
                    val st = g.getService(SERVICE_UUID)?.getCharacteristic(STATUS_UUID)
                    if (st != null) enableNotifications(g, st) else writeStart(g)
                }
                STATUS_UUID -> writeStart(g)
            }
        }

        // API 33+ signature.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleNotification(c.uuid, value)

        // Pre-33 signature. Still delivered on older platforms.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotification(c.uuid, c.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    /** Without this the unit stays idle and no ECG frame is ever sent. */
    @SuppressLint("MissingPermission")
    private fun writeStart(g: BluetoothGatt) {
        val c = controlChar ?: run {
            post(BleState.FAILED, "Sensor unit is missing the control characteristic")
            return
        }
        val seconds = (windowSamples / 250).coerceIn(1, 255)
        // Opcode 0x03 = capture for `seconds`, then auto-stop. Preferred over a
        // bare start so the unit stops sampling even if this app dies mid-capture.
        val payload = byteArrayOf(0x03, seconds.toByte())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        post(BleState.STREAMING)
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            STATUS_UUID -> SensorStatus.parse(value)?.let { s ->
                main.post { status = s; onStatus(s) }
            }

            ECG_UUID -> {
                val samples = parser.onFrame(value)
                if (samples.isEmpty()) return
                val done = parser.complete
                val progress = parser.progress
                val gap = parser.gapDetected
                main.post {
                    onSamples(samples)
                    onProgress(progress)
                    if (done && state == BleState.STREAMING) {
                        state = BleState.COMPLETE
                        onState(BleState.COMPLETE, null)
                        onWindow(parser.window(), gap)
                        stopStreaming()
                    }
                }
            }
        }
    }

    // ---- Teardown ------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun stopStreaming() {
        val g = gatt ?: return
        val c = controlChar ?: return
        val payload = byteArrayOf(0x02, 0x00) // stop
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                c.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (_: SecurityException) {
            // Best effort. The 0x03 opcode already armed an auto-stop.
        }
    }

    /**
     * Disconnects and releases the GATT client.
     *
     * **`disconnect()` and `close()` are not interchangeable and the order
     * matters.** `close()` releases the client interface immediately; calling it
     * straight after `disconnect()` tears the interface down before the
     * link-layer disconnect has been sent, so the peripheral never learns the
     * central went away. An ESP32 has one connection slot and stops advertising
     * while it is occupied, so the unit then becomes permanently invisible to
     * scanning - it works once, then "no sensor unit found" forever.
     *
     * So: request the disconnect, let [gattCallback] close on confirmation, and
     * keep a deadline in case the callback never arrives.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        val g = gatt
        if (g == null) {
            releaseGatt()
            return
        }
        try {
            g.disconnect()
            main.postDelayed({ releaseGatt() }, CLOSE_GRACE_MS)
        } catch (_: SecurityException) {
            releaseGatt()
        }
    }

    /** Final teardown. Safe to call twice. */
    @SuppressLint("MissingPermission")
    private fun releaseGatt() {
        try {
            gatt?.close()
        } catch (_: SecurityException) {
            // The connection dies with the process regardless.
        }
        gatt = null
        controlChar = null
    }

    companion object {
        // contracts/ble.md §1. Changing one of these without changing the
        // firmware produces a unit that scans forever and finds nothing.
        val SERVICE_UUID: UUID = UUID.fromString("7a9c0100-5d2e-4b81-9f13-2c6e0a4d55e0")
        val ECG_UUID: UUID = UUID.fromString("7a9c0101-5d2e-4b81-9f13-2c6e0a4d55e0")
        val STATUS_UUID: UUID = UUID.fromString("7a9c0102-5d2e-4b81-9f13-2c6e0a4d55e0")
        val CONTROL_UUID: UUID = UUID.fromString("7a9c0103-5d2e-4b81-9f13-2c6e0a4d55e0")
        val PPG_UUID: UUID = UUID.fromString("7a9c0104-5d2e-4b81-9f13-2c6e0a4d55e0")

        /** Standard Client Characteristic Configuration Descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 56-byte frames need more than the 23-byte default ATT MTU. */
        const val DESIRED_MTU = 185

        /** Advertised name prefix, from the firmware's `ArogyaX-%02X%02X`. */
        const val NAME_PREFIX = "ArogyaX"

        /** How long to wait for a disconnect to be confirmed before forcing close. */
        const val CLOSE_GRACE_MS = 600L
    }
}
