package com.sajidalam.familygps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LocationListener {

    companion object {
        private const val SERVER_BASE_URL = "http://192.168.1.4:8085"
        private const val HEALTH_URL = "$SERVER_BASE_URL/health"
        private const val SYNC_URL = "$SERVER_BASE_URL/api/sync"

        private const val PREFS_NAME = "srk_gps_sync_prefs"
        private const val KEY_QUEUE = "queued_telemetry_json"
        private const val KEY_USER_ID = "device_user_id"
        private const val KEY_MODE = "tracking_mode"
        private const val KEY_LAST_SYNC = "last_sync_time"

        private const val LOCATION_PERM_REQUEST = 1001
        private const val SYNC_INTERVAL_MS = 5000L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var locationManager: LocationManager? = null
    private var isTracking = false
    private var isServerReachable = false
    private var lastLat: Double = 31.4922
    private var lastLng: Double = 74.3382
    private var currentMode: String = "Office"
    private var modeLocked: Boolean = true

    private var tvStatusBanner: TextView? = null
    private var tvDetails: TextView? = null
    private var etDeviceId: EditText? = null
    private var btnOffice: Button? = null
    private var btnHome: Button? = null
    private var btnStartTracking: Button? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (isTracking) {
                captureAndEnqueuePoint()
                flushQueueToServerAsync()
            } else {
                checkServerHealthAsync()
            }
            mainHandler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindViewsIfPresent()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentMode = prefs.getString(KEY_MODE, "Office") ?: "Office"
        val savedUserId = prefs.getString(KEY_USER_ID, "shahid") ?: "shahid"
        etDeviceId?.setText(savedUserId)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        setupListeners()
        updateUiState()

        checkServerHealthAsync()
        mainHandler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
    }

    private fun bindViewsIfPresent() {
        val bannerId = resources.getIdentifier("tvStatusBanner", "id", packageName)
        val detailsId = resources.getIdentifier("tvDetails", "id", packageName)
        val userIdInputId = resources.getIdentifier("etDeviceId", "id", packageName)
        val officeBtnId = resources.getIdentifier("btnOffice", "id", packageName)
        val homeBtnId = resources.getIdentifier("btnHome", "id", packageName)
        val startBtnId = resources.getIdentifier("btnStartTracking", "id", packageName)

        if (bannerId != 0) tvStatusBanner = findViewById(bannerId)
        if (detailsId != 0) tvDetails = findViewById(detailsId)
        if (userIdInputId != 0) etDeviceId = findViewById(userIdInputId)
        if (officeBtnId != 0) btnOffice = findViewById(officeBtnId)
        if (homeBtnId != 0) btnHome = findViewById(homeBtnId)
        if (startBtnId != 0) btnStartTracking = findViewById(startBtnId)
    }

    private fun setupListeners() {
        btnOffice?.setOnClickListener {
            if (!modeLocked) {
                currentMode = "Office"
                savePrefs()
                updateUiState()
            }
        }

        btnHome?.setOnClickListener {
            if (!modeLocked) {
                currentMode = "Home"
                savePrefs()
                updateUiState()
            }
        }

        btnStartTracking?.setOnClickListener {
            savePrefs()
            if (!isTracking) {
                startTrackingSession()
            } else {
                flushQueueToServerAsync()
            }
        }
    }

    private fun savePrefs() {
        val userIdText = etDeviceId?.text?.toString()?.trim().orEmpty().ifEmpty { "shahid" }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_USER_ID, userIdText)
            .putString(KEY_MODE, currentMode)
            .apply()
    }

    private fun startTrackingSession() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERM_REQUEST
            )
            return
        }
        isTracking = true
        requestLocationUpdatesSafe()
        captureAndEnqueuePoint()
        flushQueueToServerAsync()
        updateUiState()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdatesSafe() {
        if (!hasLocationPermission()) return
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                3000L,
                1f,
                this
            )
        } catch (_: Exception) {
        }
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                3000L,
                1f,
                this
            )
        } catch (_: Exception) {
        }
        try {
            val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                lastLat = lastKnown.latitude
                lastLng = lastKnown.longitude
            }
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLat = location.latitude
        lastLng = location.longitude
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level else 51
        } catch (_: Exception) {
            51
        }
    }

    private fun captureAndEnqueuePoint() {
        val userId = etDeviceId?.text?.toString()?.trim().orEmpty().ifEmpty { "shahid" }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val item = JSONObject().apply {
            put("device_id", userId)
            put("mode", currentMode)
            put("lat", lastLat)
            put("lng", lastLng)
            put("battery", getBatteryPercentage())
            put("timestamp", timestamp)
        }

        val queue = getQueueArray()
        queue.put(item)
        saveQueueArray(queue)
        updateUiState()
    }

    private fun getQueueArray(): JSONArray {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveQueueArray(array: JSONArray) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_QUEUE, array.toString())
            .apply()
    }

    private fun checkServerHealthAsync() {
        executor.execute {
            val reachable = pingHealthEndpoint()
            mainHandler.post {
                isServerReachable = reachable
                updateUiState()
            }
        }
    }

    private fun pingHealthEndpoint(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(HEALTH_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                body.isNotBlank()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun flushQueueToServerAsync() {
        executor.execute {
            val queue = getQueueArray()
            if (queue.length() == 0) {
                val reachable = pingHealthEndpoint()
                mainHandler.post {
                    isServerReachable = reachable
                    updateUiState()
                }
                return@execute
            }

            val payload = JSONObject().apply {
                put("records", queue)
            }.toString()

            val sent = postSyncPayload(payload)
            mainHandler.post {
                if (sent) {
                    isServerReachable = true
                    saveQueueArray(JSONArray())
                    val nowTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_LAST_SYNC, nowTime)
                        .apply()
                } else {
                    isServerReachable = pingHealthEndpoint()
                }
                updateUiState()
            }
        }
    }

    private fun postSyncPayload(jsonBody: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(SYNC_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun updateUiState() {
        val queuedCount = getQueueArray().length()
        val userId = etDeviceId?.text?.toString()?.trim().orEmpty().ifEmpty { "shahid" }
        val lastSync = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SYNC, "13:43:30") ?: "13:43:30"

        if (isServerReachable) {
            tvStatusBanner?.text = "GPS: CONNECTED & SYNCING ($currentMode)\n$userId ($currentMode) | Queued: $queuedCount"
            tvStatusBanner?.setBackgroundColor(Color.parseColor("#1B5E20"))
        } else {
            tvStatusBanner?.text = "GPS: OFFLINE BUFFER ($currentMode)\n$userId ($currentMode) | Saving locally ($queuedCount queued)"
            tvStatusBanner?.setBackgroundColor(Color.parseColor("#37474F"))
        }

        tvDetails?.text = "Last Sync: $lastSync\nBattery: ${getBatteryPercentage()}% | Queued: $queuedCount\nCoords: ${String.format(Locale.US, "%.4f", lastLat)}, ${String.format(Locale.US, "%.4f", lastLng)}"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERM_REQUEST && hasLocationPermission()) {
            startTrackingSession()
        } else if (requestCode == LOCATION_PERM_REQUEST) {
            Toast.makeText(this, "لوکیشن کی اجازت درکار ہے", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(syncRunnable)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        executor.shutdown()
    }
}
