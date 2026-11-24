package com.sajidalam.familygps

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_OFFICE = "Office"
        const val MODE_HOME = "Home"
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
    private var tvStayAndDirection: TextView? = null

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

        val tvLastSync = findViewById<TextView>(R.id.tvLastSync)
        val tvBatteryInfo = findViewById<TextView>(R.id.tvBatteryInfo)
        val stayView = findViewById<TextView>(R.id.tvStayAndDirection)
        this.tvStayAndDirection = stayView

        val btnStartTracking = findViewById<Button>(R.id.btnStartTracking)
        val btnStopTracking = findViewById<Button>(R.id.btnStopTracking)

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
        updateBatteryAndInfo(tvBatteryInfo, stayView)

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
            tvLastSync.text = "Last Sync: $currentTime"
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
            etDeviceId.isEnabled = false
            refreshModeButtonsState(btnModeOffice, btnModeFamily)

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

            uiHandler.removeCallbacksAndMessages(null)
            uiHandler.postDelayed({
                if (!isSessionActive) return@postDelayed
                isConnectingState = false
                val syncTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                tvLastSync.text = "Last Sync: $syncTime"
                updateBatteryAndInfo(tvBatteryInfo, stayView)
                applyVisualState()
            }, 1200)
        }

        btnStopTracking.setOnClickListener {
            uiHandler.removeCallbacksAndMessages(null)
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

        if (isConnectingState || !isInternetAvailable) {
            moveIconToBottom()
            startSpin3D()
            stopRadarPulse()
            ivRadarSignal?.visibility = View.INVISIBLE

            statusBanner?.setBackgroundColor(Color.parseColor("#EF6C00"))
            if (!isInternetAvailable) {
                tvGpsStatus?.text = "NO INTERNET: RECONNECTING ($currentMode)"
                tvStatusDetail?.text = "$label | Waiting for internet signal..."
                tvStayAndDirection?.text = "Direction / Route: Waiting for Network ($currentMode)"
            } else {
                tvGpsStatus?.text = "GPS: CONNECTING ($currentMode)..."
                tvStatusDetail?.text = "$label | Establishing connection..."
            }
        } else {
            stopSpin3D()
            moveIconToHeader()
            ivRadarSignal?.visibility = View.VISIBLE
            startRadarPulse()

            statusBanner?.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus?.text = "GPS: CONNECTED & TRACKING ($currentMode)"
            tvStatusDetail?.text = "$label | Live Signal Active"
            tvStayAndDirection?.text = "Direction / Route: Monitoring ($currentMode) | Dwell Time: 0m"
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
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
                        if (isSessionActive) applyVisualState()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    runOnUiThread {
                        val hasNet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        isInternetAvailable = hasNet
                        if (isSessionActive) applyVisualState()
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        isInternetAvailable = checkCurrentInternet()
                        if (isSessionActive) applyVisualState()
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
        tvStay.text = "Direction / Route: Monitoring ($modeName) | Dwell Time: 0m"
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
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
