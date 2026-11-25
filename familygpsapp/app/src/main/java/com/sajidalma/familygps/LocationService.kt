package com.sajidalam.familygps

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class LocationService : Service() {

    companion object {
        const val EXTRA_DEVICE_ID = "DEVICE_ID"
        const val EXTRA_USER_ID = "USER_ID"
        const val EXTRA_TOKEN = "TOKEN"
        const val EXTRA_MODE = "MODE"
        const val EXTRA_STEPS_FROM_OFFICE = "STEPS_FROM_OFFICE"
        const val EXTRA_DWELL_MINUTES = "DWELL_MINUTES"
        const val EXTRA_STATUS = "STATUS"
        const val EXTRA_DESTINATION = "DESTINATION"

        const val ACTION_BOOKER_RETURN = "com.sajidalam.familygps.ACTION_BOOKER_RETURN"
        const val ACTION_AUTO_DISCONNECT = "com.sajidalam.familygps.ACTION_AUTO_DISCONNECT"

        private const val BASE_URL = "http://192.168.1.3:8085"
        private const val SYNC_ENDPOINT = "$BASE_URL/api/sync"
        private const val BOOKER_RETURN_ENDPOINT = "$BASE_URL/api/booker-return"
        private const val BUFFER_FILE_NAME = "srk3_offline_buffer.json"
        private const val CHANNEL_ID = "GPS_CHANNEL"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var currentDeviceId: String = "android-unit-01"
    private var currentUserId: String = "shahid"
    private var currentToken: String = "SRK3-SHAHIDFARO-213653-5510"
    private var currentMode: String = "office"
    private var stepsFromOffice: Int = 0
    private var dwellMinutes: Int = 0
    private var currentStatus: String = "moving"
    private var totalStopsCount: Int = 0
    private var lastLocation: Location? = null
    private var lastDisconnectGapStart: String? = null
    private var isLocationUpdatesActive: Boolean = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentDeviceId = intent?.getStringExtra(EXTRA_DEVICE_ID) ?: currentDeviceId
        currentUserId = intent?.getStringExtra(EXTRA_USER_ID) ?: currentDeviceId
        currentToken = intent?.getStringExtra(EXTRA_TOKEN) ?: currentToken
        currentMode = intent?.getStringExtra(EXTRA_MODE) ?: currentMode
        stepsFromOffice = intent?.getIntExtra(EXTRA_STEPS_FROM_OFFICE, stepsFromOffice) ?: stepsFromOffice
        dwellMinutes = intent?.getIntExtra(EXTRA_DWELL_MINUTES, dwellMinutes) ?: dwellMinutes
        currentStatus = intent?.getStringExtra(EXTRA_STATUS) ?: currentStatus

        val notification = buildNotification("Tracking active for $currentUserId")
        startForeground(1, notification)

        when (intent?.action) {
            ACTION_BOOKER_RETURN -> {
                val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: "Main Depot / Return Zone"
                handleBookerReturnAndAutoDisconnect(destination)
                return START_NOT_STICKY
            }
            ACTION_AUTO_DISCONNECT -> {
                stopTrackingCleanly()
                return START_NOT_STICKY
            }
        }

        if (!isLocationUpdatesActive) {
            startLocationUpdates()
        }

        // اگر نیٹ موجود ہے تو پہلے سے محفوظ آف لائن بفر بھیجیں
        flushOfflineBufferIfOnline()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocation = location

                if (currentStatus == "stopped") {
                    totalStopsCount += 1
                }

                val battery = getBatteryLevel()
                val now = Date()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now)
                val shortTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)

                val isOnline = isNetworkAvailable()
                val gpsConnected = isOnline && (stepsFromOffice !in 60..70)

                val payload = JSONObject().apply {
                    put("user_id", currentUserId)
                    put("device_id", currentDeviceId)
                    put("token", currentToken)
                    put("mode", currentMode)
                    put("steps_from_office", stepsFromOffice)
                    put("gps_connected", gpsConnected)
                    put("lat", location.latitude)
                    put("lng", location.longitude)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("dwell_minutes", dwellMinutes)
                    put("status", currentStatus)
                    put("battery", battery)
                    put("timestamp", timestamp)
                    put("sync_time", shortTime)
                }

                if (!isOnline) {
                    if (lastDisconnectGapStart == null) {
                        lastDisconnectGapStart = shortTime
                    }
                    payload.put("offline_buffered", true)
                    payload.put("gap_from", lastDisconnectGapStart)
                    payload.put("gap_to", shortTime)
                    saveToOfflineBuffer(payload)
                } else {
                    val hadGap = lastDisconnectGapStart != null
                    if (hadGap) {
                        payload.put("offline_buffered", true)
                        payload.put("gap_from", lastDisconnectGapStart)
                        payload.put("gap_to", shortTime)
                    } else {
                        payload.put("offline_buffered", false)
                    }

                    sendPayloadAsync(SYNC_ENDPOINT, payload) { success ->
                        if (success && hadGap) {
                            lastDisconnectGapStart = null
                        }
                        if (success) {
                            flushOfflineBufferIfOnline()
                        }
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            isLocationUpdatesActive = true
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun handleBookerReturnAndAutoDisconnect(destination: String) {
        val nowTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val summary = JSONObject().apply {
            put("total_stops", totalStopsCount)
            put("last_lat", lastLocation?.latitude ?: 0.0)
            put("last_lng", lastLocation?.longitude ?: 0.0)
            put("returned_at", nowTime)
            put("status", "completed_auto_saved")
        }

        val returnPayload = JSONObject().apply {
            put("user_id", currentUserId)
            put("device_id", currentDeviceId)
            put("token", currentToken)
            put("destination", destination)
            put("auto_disconnected", true)
            put("summary", summary)
        }

        sendPayloadAsync(BOOKER_RETURN_ENDPOINT, returnPayload) { success ->
            if (!success) {
                returnPayload.put("offline_buffered", true)
                saveToOfflineBuffer(returnPayload)
            }
            Handler(Looper.getMainLooper()).post {
                stopTrackingCleanly()
            }
        }
    }

    private fun stopTrackingCleanly() {
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isLocationUpdatesActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getBufferFile(): File {
        return File(filesDir, BUFFER_FILE_NAME)
    }

    @Synchronized
    private fun saveToOfflineBuffer(item: JSONObject) {
        try {
            val file = getBufferFile()
            val array = if (file.exists() && file.readText().isNotBlank()) {
                JSONArray(file.readText())
            } else {
                JSONArray()
            }
            array.put(item)
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun flushOfflineBufferIfOnline() {
        if (!isNetworkAvailable()) return
        val file = getBufferFile()
        if (!file.exists()) return

        Thread {
            try {
                val raw = file.readText()
                if (raw.isBlank()) return@Thread
                val array = JSONArray(raw)
                if (array.length() == 0) return@Thread

                val remaining = JSONArray()
                val nowTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    obj.put("offline_buffered", true)
                    if (!obj.has("gap_from") || obj.isNull("gap_from")) {
                        obj.put("gap_from", nowTime)
                    }
                    obj.put("gap_to", nowTime)

                    val sent = postJsonSync(SYNC_ENDPOINT, obj)
                    if (!sent) {
                        remaining.put(obj)
                    }
                }

                synchronized(this) {
                    if (remaining.length() == 0) {
                        if (file.exists()) file.delete()
                        lastDisconnectGapStart = null
                    } else {
                        file.writeText(remaining.toString())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendPayloadAsync(endpoint: String, payload: JSONObject, onComplete: ((Boolean) -> Unit)? = null) {
        Thread {
            val ok = postJsonSync(endpoint, payload)
            onComplete?.invoke(ok)
        }.start()
    }

    private fun postJsonSync(endpoint: String, payload: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            code in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SRK 3 GPS Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isLocationUpdatesActive = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SRK 3 GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
