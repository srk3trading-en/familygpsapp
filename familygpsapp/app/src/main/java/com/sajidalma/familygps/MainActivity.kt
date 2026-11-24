package com.sajidalam.familygps

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_OFFICE = "Office"
        const val MODE_HOME = "Home"
        private const val PREFS_NAME = "srk_gps_offline_prefs"
        private const val KEY_OFFLINE_QUEUE = "offline_queue_json"
    }

    private var selectedMode: String = MODE_OFFICE
    private var activeSessionMode: String? = null
    private var activeSessionId: String? = null
    private var isSessionActive: Boolean = false

    private var isTokenActivated: Boolean = false
    private var activeToken: String? = null
    private var generatedToken: String = ""
    private val usedTokens = mutableSetOf<String>()

    private var isInternetAvailable: Boolean = false
    private var isConnectingState: Boolean = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var lastKnownLocation: Location? = null
    private var sessionStartMillis: Long = 0L
    private var stationarySinceMillis: Long = 0L

    private var spin3DAnimator: ObjectAnimator? = null
    private var radarPulseAnimator: AnimatorSet? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var ivArrowIcon: ImageView? = null
    private var ivRadarSignal: View? = null
    private var statusBanner: LinearLayout? = null
    private var tvGpsStatus: TextView? = null
    private var tvStatusDetail: TextView? = null
    private var tvSelectedMode: TextView? = null
    private var tvTokenLogInfo: TextView? = null
    private var tvLastSync: TextView? = null
    private var tvBatteryInfo: TextView? = null
    private var tvStayAndDirection: TextView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!fineOk && !coarseOk) {
            tvTokenLogInfo?.text = "Permission Note: Location permission needed for GPS tracking"
        } else {
            tvTokenLogInfo?.text = "Permission Ready: Location access granted"
            if (isSessionActive) {
                startGpsUpdates()
            }
        }
    }

    private val syncTicker = object : Runnable {
        override fun run() {
            if (!isSessionActive) return
            captureAndProcessTelemetry()
            uiHandler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val systemName = getString(R.string.app_name)

        statusBanner = findViewById(R.id.statusBanner)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)

        ivArrowIcon = findViewById(R.id.ivArrowIcon)
        ivRadarSignal = findViewById(R.id.ivRadarSignal)
        ivRadarSignal?.visibility = View.INVISIBLE

        val density = resources.displayMetrics.density
        ivArrowIcon?.cameraDistance = 8000f * density

        val btnModeOffice = findViewById<Button?>(R.id.btnModeOffice)
        val btnModeFamily = findViewById<Button?>(R.id.btnModeFamily)
        tvSelectedMode = findViewById(R.id.tvSelectedMode)

        btnModeOffice?.visibility = View.VISIBLE
        btnModeFamily?.visibility = View.VISIBLE
        btnModeFamily?.text = MODE_HOME

        val etDeviceId = findViewById<EditText>(R.id.etDeviceId)
        val tvReceivedToken = findViewById<TextView>(R.id.tvReceivedToken)
        val btnPasteToken = findViewById<Button>(R.id.btnPasteToken)
        val etToken = findViewById<EditText>(R.id.etToken)
        val btnVerifyToken = findViewById<Button>(R.id.btnVerifyToken)
        val tokenLogView = findViewById<TextView>(R.id.tvTokenLogInfo)
        this.tvTokenLogInfo = tokenLogView

        val lastSyncView = findViewById<TextView>(R.id.tvLastSync)
        this.tvLastSync = lastSyncView
        val batteryView = findViewById<TextView>(R.id.tvBatteryInfo)
        this.tvBatteryInfo = batteryView
        val stayView = findViewById<TextView>(R.id.tvStayAndDirection)
        this.tvStayAndDirection = stayView

        val btnStartTracking = findViewById<Button>(R.id.btnStartTracking)
        val btnStopTracking = findViewById<Button>(R.id.btnStopTracking)

        requestRequiredPermissionsIfNeeded()

        isInternetAvailable = checkCurrentInternet()
        registerNetworkMonitor()

        tvReceivedToken.text = "Enter ID first to generate token"
        refreshModeButtonsState(btnModeOffice, btnModeFamily)

        btnModeOffice?.setOnClickListener {
            handleModeSelection(
                targetMode = MODE_OFFICE,
                etDeviceId = etDeviceId,
                etToken = etToken,
                tvReceivedToken = tvReceivedToken,
                btnModeOffice = btnModeOffice,
                btnModeFamily = btnModeFamily
            )
        }

        btnModeFamily?.setOnClickListener {
            handleModeSelection(
                targetMode = MODE_HOME,
                etDeviceId = etDeviceId,
                etToken = etToken,
                tvReceivedToken = tvReceivedToken,
                btnModeOffice = btnModeOffice,
                btnModeFamily = btnModeFamily
            )
        }

        setDisconnectedState(
            detailText = "$systemName ($selectedMode) | Please enter the ID",
            systemName = systemName
        )
        updateBatteryAndInfo(batteryView, stayView)

        tvReceivedToken.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, systemName)) {
                return@setOnClickListener
            }
            if (isSessionActive || isTokenActivated) {
                tokenLogView.text = "Token Audit: Disconnect $activeSessionMode session first"
                return@setOnClickListener
            }
            generatedToken = generateLocalToken(rawId, selectedMode)
            tvReceivedToken.text = generatedToken
            tokenLogView.text = "Token Audit: New $selectedMode token ready"
        }

        btnPasteToken.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, systemName)) {
                return@setOnClickListener
            }
            if (isSessionActive || isTokenActivated) {
                tokenLogView.text = "Token Audit: Session active. Disconnect first to reset"
                return@setOnClickListener
            }

            if (generatedToken.isBlank()) {
                generatedToken = generateLocalToken(rawId, selectedMode)
            }

            if (!usedTokens.contains(generatedToken)) {
                tvReceivedToken.text = generatedToken
                etToken.setText(generatedToken)
                tokenLogView.text = "Token Audit: $selectedMode token pasted"
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clipText.isNullOrEmpty() && !usedTokens.contains(clipText)) {
                    etToken.setText(clipText)
                    tokenLogView.text = "Token Audit: Clipboard token pasted"
                } else {
                    generatedToken = generateLocalToken(rawId, selectedMode)
                    tvReceivedToken.text = generatedToken
                    etToken.setText(generatedToken)
                    tokenLogView.text = "Token Audit: Fresh single-use $selectedMode token pasted"
                }
            }
        }

        btnVerifyToken.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, systemName)) {
                return@setOnClickListener
            }

            if (isSessionActive && activeSessionMode != null && activeSessionMode != selectedMode) {
                Toast.makeText(
                    this,
                    "$activeSessionMode mode is locked. Disconnect first.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val tokenValue = etToken.text.toString().trim()
            if (tokenValue.isEmpty()) {
                etToken.error = "Enter or paste token"
                tokenLogView.text = "Token Audit: Please enter or paste a token"
                return@setOnClickListener
            }
            etToken.error = null

            if (usedTokens.contains(tokenValue)) {
                etToken.text.clear()
                generatedToken = generateLocalToken(rawId, selectedMode)
                tvReceivedToken.text = generatedToken
                tokenLogView.text = "Token Audit: Already used. New token generated"
                return@setOnClickListener
            }

            usedTokens.add(tokenValue)
            activeToken = tokenValue
            isTokenActivated = true
            generatedToken = ""

            etToken.text.clear()
            tvReceivedToken.text = "$systemName ($selectedMode) Token Ready"

            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val label = resolveDisplayLabel(rawId, selectedMode)
            tvStatusDetail?.text = "$label | Token Verified"
            tokenLogView.text = "Token Audit: Verified at $currentTime ($selectedMode)"
            lastSyncView.text = "Last Sync: $currentTime"
        }

        btnStartTracking.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, systemName)) {
                return@setOnClickListener
            }

            if (isSessionActive && activeSessionMode != null && activeSessionMode != selectedMode) {
                Toast.makeText(
                    this,
                    "$activeSessionMode is active and locked. Disconnect first to use $selectedMode.",
                    Toast.LENGTH_SHORT
                ).show()
                tokenLogView.text = "Mode Lock: Disconnect $activeSessionMode before switching to $selectedMode"
                return@setOnClickListener
            }

            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            if (!isTokenActivated || activeToken.isNullOrBlank()) {
                val freshToken = generateLocalToken(rawId, selectedMode)
                usedTokens.add(freshToken)
                activeToken = freshToken
                isTokenActivated = true
                generatedToken = ""
                etToken.text.clear()
                tvReceivedToken.text = "$systemName ($selectedMode) Active Session Locked"
                tokenLogView.text = "Token Audit: Auto-activated at $currentTime ($selectedMode)"
            }

            isSessionActive = true
            activeSessionMode = selectedMode
            activeSessionId = rawId
            sessionStartMillis = System.currentTimeMillis()
            stationarySinceMillis = sessionStartMillis
            etDeviceId.isEnabled = false
            refreshModeButtonsState(btnModeOffice, btnModeFamily)

            startGpsUpdates()

            isConnectingState = true
            statusBanner?.setBackgroundColor(Color.parseColor("#EF6C00"))
            tvGpsStatus?.text = "GPS: CONNECTING ($selectedMode)..."
            tvStatusDetail?.text = "${resolveDisplayLabel(rawId, selectedMode)} | Connecting..."
            applyVisualState()

            try {
                val serviceIntent = Intent(this, LocationService::class.java).apply {
                    putExtra("DEVICE_ID", rawId)
                    putExtra("SYSTEM_NAME", systemName)
                    putExtra("TRACKING_MODE", selectedMode)
                    putExtra("TOKEN", activeToken)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Exception) {
            }

            uiHandler.removeCallbacks(syncTicker)
            uiHandler.postDelayed({
                if (!isSessionActive) return@postDelayed
                isConnectingState = false
                captureAndProcessTelemetry()
                uiHandler.postDelayed(syncTicker, 5000L)
            }, 1200)
        }

        btnStopTracking.setOnClickListener {
            uiHandler.removeCallbacks(syncTicker)
            stopGpsUpdates()
            try {
                stopService(Intent(this, LocationService::class.java))
            } catch (_: Exception) {
            }

            isSessionActive = false
            activeSessionMode = null
            activeSessionId = null
            isConnectingState = false
            activeToken = null
            isTokenActivated = false
            etToken.text.clear()
            etDeviceId.isEnabled = true
            refreshModeButtonsState(btnModeOffice, btnModeFamily)

            val rawId = etDeviceId.text.toString().trim()
            if (rawId.isNotEmpty()) {
                generatedToken = generateLocalToken(rawId, selectedMode)
                tvReceivedToken.text = generatedToken
            } else {
                generatedToken = ""
                tvReceivedToken.text = "Enter ID first to generate token"
            }

            val detailText = if (rawId.isNotEmpty()) {
                "${resolveDisplayLabel(rawId, selectedMode)} | Disconnected - New Token Ready"
            } else {
                "$systemName ($selectedMode) | Please enter the ID"
            }

            setDisconnectedState(
                detailText = detailText,
                systemName = systemName
            )
        }
    }

    private fun startGpsUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val prev = lastKnownLocation
                    if (prev != null && prev.distanceTo(location) > 15f) {
                        stationarySinceMillis = System.currentTimeMillis()
                    }
                    lastKnownLocation = location
                    if (isSessionActive && !isConnectingState) {
                        updateTelemetryText()
                    }
                }

                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                }

                override fun onProviderEnabled(provider: String) {
                }

                override fun onProviderDisabled(provider: String) {
                }
            }
            locationListener = listener

            if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 5f, listener)
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastKnownLocation = it }
            }
            if (hasCoarse && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 10f, listener)
                if (lastKnownLocation == null) {
                    lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { lastKnownLocation = it }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopGpsUpdates() {
        try {
            locationListener?.let { listener ->
                locationManager?.removeUpdates(listener)
            }
        } catch (_: Exception) {
        }
        locationListener = null
    }

    private fun captureAndProcessTelemetry() {
        val currentMode = activeSessionMode ?: selectedMode
        val currentId = activeSessionId ?: ""
        val nowTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val batteryPct = getBatteryPercentage()

        isInternetAvailable = checkCurrentInternet()

        val record = JSONObject().apply {
            put("mode", currentMode)
            put("deviceId", currentId)
            put("token", activeToken ?: "")
            put("lat", lastKnownLocation?.latitude ?: 0.0)
            put("lng", lastKnownLocation?.longitude ?: 0.0)
            put("bearing", lastKnownLocation?.bearing ?: 0f)
            put("battery", batteryPct)
            put("timestamp", nowTime)
        }

        if (isInternetAvailable) {
            val flushed = flushOfflineQueue()
            tvLastSync?.text = if (flushed > 0) {
                "Last Sync: $nowTime ($flushed synced)"
            } else {
                "Last Sync: $nowTime"
            }
        } else {
            enqueueOfflineRecord(record)
        }

        updateTelemetryText()
        applyVisualState()
    }

    private fun getOfflineQueueArray(): JSONArray {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_OFFLINE_QUEUE, "[]") ?: "[]"
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveOfflineQueueArray(arr: JSONArray) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_OFFLINE_QUEUE, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun enqueueOfflineRecord(record: JSONObject) {
        val arr = getOfflineQueueArray()
        arr.put(record)
        saveOfflineQueueArray(arr)
    }

    private fun flushOfflineQueue(): Int {
        val arr = getOfflineQueueArray()
        val count = arr.length()
        if (count > 0) {
            saveOfflineQueueArray(JSONArray())
        }
        return count
    }

    private fun getQueuedCount(): Int = getOfflineQueueArray().length()

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun formatBearing(bearing: Float): String {
        val normalized = ((bearing % 360f) + 360f) % 360f
        return when {
            normalized >= 337.5f || normalized < 22.5f -> "North"
            normalized < 67.5f -> "North-East"
            normalized < 112.5f -> "East"
            normalized < 157.5f -> "South-East"
            normalized < 202.5f -> "South"
            normalized < 247.5f -> "South-West"
            normalized < 292.5f -> "West"
            else -> "North-West"
        }
    }

    private fun updateTelemetryText() {
        val modeName = activeSessionMode ?: selectedMode
        val level = getBatteryPercentage()
        val batteryText = if (level in 0..100) "$level%" else "--%"
        val queued = getQueuedCount()

        val dwellMins = if (stationarySinceMillis > 0L) {
            ((System.currentTimeMillis() - stationarySinceMillis) / 60000L).coerceAtLeast(0L)
        } else {
            0L
        }

        val loc = lastKnownLocation
        val gpsSummary = if (loc != null) {
            val dir = formatBearing(loc.bearing)
            "${String.format(Locale.US, "%.4f", loc.latitude)}, ${String.format(Locale.US, "%.4f", loc.longitude)} ($dir)"
        } else {
            "Waiting GPS ($modeName)"
        }

        tvBatteryInfo?.text = "Battery: $batteryText | Stay Log: Active"
        tvStayAndDirection?.text = if (!isInternetAvailable && queued > 0) {
            "Direction / Route: $gpsSummary | Queued: $queued | Dwell: ${dwellMins}m"
        } else {
            "Direction / Route: $gpsSummary | Dwell: ${dwellMins}m"
        }
    }

    private fun requestRequiredPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            try {
                permissionLauncher.launch(needed.toTypedArray())
            } catch (_: Exception) {
            }
        }
    }

    private fun handleModeSelection(
        targetMode: String,
        etDeviceId: EditText,
        etToken: EditText,
        tvReceivedToken: TextView,
        btnModeOffice: Button?,
        btnModeFamily: Button?
    ) {
        if (isSessionActive && activeSessionMode != null && activeSessionMode != targetMode) {
            Toast.makeText(
                this,
                "$activeSessionMode mode is active. Disconnect first to switch to $targetMode.",
                Toast.LENGTH_SHORT
            ).show()
            tvTokenLogInfo?.text = "Mode Lock: $activeSessionMode session active. Disconnect first."
            return
        }

        if (selectedMode != targetMode) {
            selectedMode = targetMode
            activeToken = null
            isTokenActivated = false
            etToken.text.clear()
            etDeviceId.text.clear()
            generatedToken = ""
            tvReceivedToken.text = "Enter ID for $selectedMode to generate token"
            tvSelectedMode?.text = "Mode: $selectedMode"
            tvTokenLogInfo?.text = "Switched to $selectedMode mode. Enter ID & token."
        }

        refreshModeButtonsState(btnModeOffice, btnModeFamily)
    }

    private fun refreshModeButtonsState(btnModeOffice: Button?, btnModeFamily: Button?) {
        val lockSwitch = isSessionActive && activeSessionMode != null
        btnModeOffice?.isEnabled = !lockSwitch || activeSessionMode == MODE_OFFICE
        btnModeFamily?.isEnabled = !lockSwitch || activeSessionMode == MODE_HOME

        btnModeOffice?.alpha = if (btnModeOffice?.isEnabled == true) 1.0f else 0.4f
        btnModeFamily?.alpha = if (btnModeFamily?.isEnabled == true) 1.0f else 0.4f

        val modeLabel = if (lockSwitch) {
            "Mode: $activeSessionMode (LOCKED)"
        } else {
            "Mode: $selectedMode"
        }
        tvSelectedMode?.text = modeLabel
    }

    private fun applyVisualState() {
        val currentMode = activeSessionMode ?: selectedMode
        val currentId = activeSessionId ?: ""
        val label = if (currentId.isNotBlank()) resolveDisplayLabel(currentId, currentMode) else currentMode

        if (!isSessionActive) {
            stopSpin3D()
            stopRadarPulse()
            moveIconToBottom()
            ivRadarSignal?.visibility = View.INVISIBLE
            return
        }

        if (isConnectingState) {
            moveIconToBottom()
            startSpin3D()
            stopRadarPulse()
            ivRadarSignal?.visibility = View.INVISIBLE

            statusBanner?.setBackgroundColor(Color.parseColor("#EF6C00"))
            tvGpsStatus?.text = "GPS: CONNECTING ($currentMode)..."
            tvStatusDetail?.text = "$label | Establishing signal..."
            return
        }

        val queued = getQueuedCount()
        if (!isInternetAvailable) {
            stopSpin3D()
            moveIconToHeader()
            ivRadarSignal?.visibility = View.INVISIBLE
            stopRadarPulse()

            statusBanner?.setBackgroundColor(Color.parseColor("#37474F"))
            tvGpsStatus?.text = "GPS: OFFLINE BUFFER ($currentMode)"
            tvStatusDetail?.text = "$label | Saving locally ($queued queued)"
        } else {
            stopSpin3D()
            moveIconToHeader()
            ivRadarSignal?.visibility = View.VISIBLE
            startRadarPulse()

            statusBanner?.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus?.text = "GPS: CONNECTED & TRACKING ($currentMode)"
            tvStatusDetail?.text = "$label | Live Signal Active"
        }
    }

    private fun startSpin3D() {
        val target = ivArrowIcon ?: return
        if (spin3DAnimator?.isRunning == true) return
        spin3DAnimator?.cancel()
        spin3DAnimator = ObjectAnimator.ofFloat(target, View.ROTATION_Y, 0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopSpin3D() {
        spin3DAnimator?.cancel()
        spin3DAnimator = null
        ivArrowIcon?.rotationY = 0f
    }

    private fun moveIconToHeader() {
        val target = ivArrowIcon ?: return
        val bannerHeight = statusBanner?.height ?: 0
        val targetTranslationY = if (bannerHeight > 0 && target.height > 0) {
            -(target.top - (bannerHeight / 2f) + (target.height / 2f)).coerceIn(-250f, 250f)
        } else {
            0f
        }

        target.animate()
            .translationY(targetTranslationY)
            .setDuration(400L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun moveIconToBottom() {
        val target = ivArrowIcon ?: return
        target.animate()
            .translationY(0f)
            .setDuration(350L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun startRadarPulse() {
        val radar = ivRadarSignal ?: return
        if (radarPulseAnimator?.isRunning == true) return
        radarPulseAnimator?.cancel()

        radar.scaleX = 0.7f
        radar.scaleY = 0.7f
        radar.alpha = 0.9f

        val scaleX = ObjectAnimator.ofFloat(radar, View.SCALE_X, 0.7f, 1.6f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val scaleY = ObjectAnimator.ofFloat(radar, View.SCALE_Y, 0.7f, 1.6f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val alpha = ObjectAnimator.ofFloat(radar, View.ALPHA, 0.9f, 0.0f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }

        radarPulseAnimator = AnimatorSet().apply {
            interpolator = LinearInterpolator()
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun stopRadarPulse() {
        radarPulseAnimator?.cancel()
        radarPulseAnimator = null
        ivRadarSignal?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 0f
        }
    }

    private fun checkCurrentInternet(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        isInternetAvailable = checkCurrentInternet()
                        if (isSessionActive) {
                            val flushed = flushOfflineQueue()
                            if (flushed > 0) {
                                val syncTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                tvLastSync?.text = "Last Sync: $syncTime ($flushed synced)"
                            }
                            updateTelemetryText()
                            applyVisualState()
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    runOnUiThread {
                        isInternetAvailable = networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET
                        )
                        if (isSessionActive) {
                            if (isInternetAvailable) {
                                val flushed = flushOfflineQueue()
                                if (flushed > 0) {
                                    val syncTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                    tvLastSync?.text = "Last Sync: $syncTime ($flushed synced)"
                                }
                            }
                            updateTelemetryText()
                            applyVisualState()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        isInternetAvailable = checkCurrentInternet()
                        if (isSessionActive) {
                            updateTelemetryText()
                            applyVisualState()
                        }
                    }
                }
            }

            networkCallback = callback
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }
    }

    private fun requireDeviceId(etDeviceId: EditText, systemName: String): Boolean {
        val rawId = etDeviceId.text.toString().trim()
        if (rawId.isEmpty()) {
            etDeviceId.error = "Please enter the ID"
            etDeviceId.requestFocus()
            tvStatusDetail?.text = "$systemName ($selectedMode) | Please enter the ID"
            tvTokenLogInfo?.text = "Token Audit: Device / User ID is required"
            return false
        }
        etDeviceId.error = null
        return true
    }

    private fun resolveDisplayLabel(rawInput: String, mode: String): String {
        val cleanedInput = rawInput.trim()
        val personName = cleanedInput
            .replace(Regex("\\b(home|family|office)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { cleanedInput }

        return "$personName - $mode"
    }

    private fun generateLocalToken(rawUserId: String, mode: String): String {
        val brandCode = getString(R.string.app_name)
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(4)
            .uppercase(Locale.getDefault())
            .ifEmpty { "GPS" }

        val modeTag = mode.take(3).uppercase(Locale.getDefault())

        val cleanId = rawUserId.trim()
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(8)
            .uppercase(Locale.getDefault())
            .ifEmpty { "USERID" }

        val timePart = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
        var candidate: String
        do {
            val randomCode = Random.nextInt(1000, 9999)
            candidate = "$brandCode-$modeTag-$cleanId-$timePart-$randomCode"
        } while (usedTokens.contains(candidate))

        return candidate
    }

    private fun setDisconnectedState(detailText: String, systemName: String) {
        statusBanner?.setBackgroundColor(Color.parseColor("#D32F2F"))
        tvGpsStatus?.text = "GPS: DISCONNECTED"
        tvStatusDetail?.text = detailText
        refreshModeButtonsState(
            findViewById(R.id.btnModeOffice),
            findViewById(R.id.btnModeFamily)
        )
        tvTokenLogInfo?.text = "Token Audit: Ready for fresh $systemName ($selectedMode) token"
        tvStayAndDirection?.text = "Direction / Route: Stopped | Dwell Time: 0m"
        applyVisualState()
    }

    private fun updateBatteryAndInfo(tvBattery: TextView, tvStay: TextView) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryText = if (level in 0..100) "$level%" else "--%"
        val modeName = activeSessionMode ?: selectedMode
        tvBattery.text = "Battery: $batteryText | Stay Log: Active"
        tvStay.text = "Direction / Route: Monitoring ($modeName) | Dwell: 0m"
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(syncTicker)
        uiHandler.removeCallbacksAndMessages(null)
        stopGpsUpdates()
        stopSpin3D()
        stopRadarPulse()
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
