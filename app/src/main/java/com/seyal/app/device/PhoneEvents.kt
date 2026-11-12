package com.seyal.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import com.seyal.app.agent.Skills
import com.seyal.app.data.Trigger

/**
 * Turns what happens to the phone into events skills can wait for: Wi-Fi and Bluetooth connecting,
 * headphones, battery level, reaching or leaving a place. Listens only for the kinds some enabled
 * skill uses, and only while the app is alive (the "Watching" service keeps it alive).
 * Also answers "what is the phone's state right now" for "only when…" conditions.
 */
object PhoneEvents {
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var locListener: LocationListener? = null
    private var lastBattery = -1
    private var lastWifi: String? = null
    /** Place label → whether the phone was inside it at the last fix. */
    private val inside = HashMap<String, Boolean>()

    private fun wants(prefix: String): Boolean = Skills.all().any { it.enabled && it.trigger?.type == "event" && it.trigger!!.event.startsWith(prefix) }
    private fun places(): List<Trigger> = Skills.all().filter { it.enabled && it.trigger?.type == "event" }.mapNotNull { it.trigger }
        .filter { (it.event.startsWith("arrive:") || it.event.startsWith("leave:")) && (it.lat != 0.0 || it.lon != 0.0) }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops listeners to match the skills now enabled. Safe to call often. */
    @Synchronized
    fun sync(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        // ---- broadcasts: battery, headphones, Bluetooth
        val needBattery = wants("battery_"); val needHead = wants("headphones_"); val needBt = wants("bluetooth_")
        receiver?.let { runCatching { c.unregisterReceiver(it) } }; receiver = null
        if (needBattery || needHead || needBt) {
            val f = IntentFilter().apply {
                if (needBattery) addAction(Intent.ACTION_BATTERY_CHANGED)
                if (needHead) addAction(Intent.ACTION_HEADSET_PLUG)
                if (needBt) { addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) }
            }
            val r = object : BroadcastReceiver() {
                private var headsetSeen = false
                override fun onReceive(context: Context, intent: Intent) { runCatching { handle(context, intent) } }
                private fun handle(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_CHANGED -> {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                            val before = lastBattery; lastBattery = level
                            if (before < 0 || before == level) return
                            thresholds("battery_below:").forEach { n -> if (before >= n && level < n) Skills.onEvent("battery_below:$n") }
                            thresholds("battery_above:").forEach { n -> if (before <= n && level > n) Skills.onEvent("battery_above:$n") }
                        }
                        Intent.ACTION_HEADSET_PLUG -> {
                            // The first broadcast only reports the state at registration.
                            if (!headsetSeen) { headsetSeen = true; return }
                            Skills.onEvent(if (intent.getIntExtra("state", 0) == 1) "headphones_in" else "headphones_out")
                        }
                        BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            @Suppress("DEPRECATION") val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            @SuppressLint("MissingPermission")
                            val name = if (Build.VERSION.SDK_INT < 31 || has(context, Manifest.permission.BLUETOOTH_CONNECT)) runCatching { d?.name }.getOrNull().orEmpty() else ""
                            Skills.onEvent((if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "bluetooth_connected:" else "bluetooth_disconnected:") + name)
                        }
                    }
                }
            }
            runCatching { if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else c.registerReceiver(r, f) }
            receiver = r
        }
        // ---- Wi-Fi
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }; netCallback = null
        if (wants("wifi_")) {
            lastWifi = wifiName(c)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // The name is known a moment after the link comes up.
                    android.os.Handler(Looper.getMainLooper()).postDelayed({
                        val name = wifiName(c) ?: ""
                        if (lastWifi == null || lastWifi != name) { lastWifi = name; Skills.onEvent("wifi_connected:$name") }
                    }, 1500)
                }
                override fun onLost(network: Network) {
                    val was = lastWifi ?: return
                    lastWifi = null
                    Skills.onEvent("wifi_disconnected:$was")
                }
            }
            runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb) }
            netCallback = cb
        }
        // ---- places
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locListener?.let { runCatching { lm.removeUpdates(it) } }; locListener = null
        if (places().isNotEmpty() && (has(c, Manifest.permission.ACCESS_FINE_LOCATION) || has(c, Manifest.permission.ACCESS_COARSE_LOCATION))) {
            val l = LocationListener { loc -> onLocation(loc) }
            @SuppressLint("MissingPermission")
            val ok = runCatching {
                val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
                // A fix every two minutes or 60 m is plenty for "reached home" and easy on the battery.
                providers.take(2).forEach { p -> lm.requestLocationUpdates(p, 120_000L, 60f, l, Looper.getMainLooper()) }
                providers.isNotEmpty()
            }.getOrDefault(false)
            if (ok) locListener = l else Skills.log("Place skills need Location switched on")
        }
    }

    private fun thresholds(prefix: String): List<Int> = Skills.all().filter { it.enabled && it.trigger?.type == "event" }
        .mapNotNull { it.trigger!!.event.takeIf { e -> e.startsWith(prefix) }?.substringAfter(':')?.trim()?.toIntOrNull() }.distinct()

    /** Decides arrive/leave for every place from one position fix. The first fix only records where the phone is. */
    private fun onLocation(loc: Location) {
        for (t in places().distinctBy { it.event.substringAfter(':').lowercase() }) {
            val label = t.event.substringAfter(':')
            val now = within(loc.latitude, loc.longitude, loc.accuracy, t.lat, t.lon, t.radius, inside[label.lowercase()])
            val before = inside.put(label.lowercase(), now)
            if (before == null || before == now) continue
            Skills.onEvent((if (now) "arrive:" else "leave:") + label)
        }
    }

    /**
     * Whether a fix counts as inside a place. A fix near the edge keeps the previous answer (so the
     * usual GPS wobble doesn't fire arrive/leave again and again), and a very vague fix changes nothing.
     */
    internal fun within(lat: Double, lon: Double, accuracy: Float, placeLat: Double, placeLon: Double, radius: Int, wasInside: Boolean?): Boolean {
        val d = distance(lat, lon, placeLat, placeLon)
        if (accuracy > 400f && wasInside != null) return wasInside
        return when {
            d <= radius -> true
            d >= radius * 1.4 + 30 -> false
            else -> wasInside ?: (d <= radius)
        }
    }

    /** Metres between two points (haversine). */
    internal fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ---------------------------------------------------------------- the phone right now

    /** The connected Wi-Fi's name; "" when connected but the name is hidden (needs Location); null when not on Wi-Fi. */
    fun wifiName(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        @Suppress("DEPRECATION")
        val ssid = runCatching { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid }.getOrNull().orEmpty().trim('"')
        return if (ssid.isBlank() || ssid.contains("unknown ssid", true)) "" else ssid
    }

    /** Battery percent and whether the phone is charging. */
    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = if (i == null) -1 else i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return level to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }
}
