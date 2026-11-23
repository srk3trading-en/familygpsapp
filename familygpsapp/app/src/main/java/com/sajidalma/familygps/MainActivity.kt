package com.srk3trading.gps // نوٹ: اگر باقی فائلیں com.sajidalam.familygps میں ہیں تو اسے وہی رکھیں اور نیچے import com.sajidalam.familygps.R شامل کر لیں

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private var selectedMode: String = "Office"
    private var isTokenActivated: Boolean = false
    private var activeToken: String? = null
    private var generatedToken: String = ""
    private val usedTokens = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusBanner = findViewById<LinearLayout>(R.id.statusBanner)
        val tvGpsStatus = findViewById<TextView>(R.id.tvGpsStatus)
        val tvStatusDetail = findViewById<TextView>(R.id.tvStatusDetail)

        val btnModeOffice = findViewById<Button>(R.id.btnModeOffice)
        val btnModeFamily = findViewById<Button>(R.id.btnModeFamily)
        val tvSelectedMode = findViewById<TextView>(R.id.tvSelectedMode)

        val etDeviceId = findViewById<EditText>(R.id.etDeviceId)
        val tvReceivedToken = findViewById<TextView>(R.id.tvReceivedToken)
        val btnPasteToken = findViewById<Button>(R.id.btnPasteToken)
        val etToken = findViewById<EditText>(R.id.etToken)
        val btnVerifyToken = findViewById<Button>(R.id.btnVerifyToken)
        val tvTokenLogInfo = findViewById<TextView>(R.id.tvTokenLogInfo)

        val tvLastSync = findViewById<TextView>(R.id.tvLastSync)
        val tvBatteryInfo = findViewById<TextView>(R.id.tvBatteryInfo)
        val tvStayAndDirection = findViewById<TextView>(R.id.tvStayAndDirection)

        val btnStartTracking = findViewById<Button>(R.id.btnStartTracking)
        val btnStopTracking = findViewById<Button>(R.id.btnStopTracking)

        // Request runtime permissions
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            100
        )

        // Generate initial local token on device for SRK3 Trading GPS System
        generatedToken = generateLocalToken(etDeviceId.text.toString())
        tvReceivedToken.text = generatedToken

        // Default mode UI
        setDisconnectedState(
            statusBanner = statusBanner,
            tvGpsStatus = tvGpsStatus,
            tvStatusDetail = tvStatusDetail,
            tvSelectedMode = tvSelectedMode,
            tvTokenLogInfo = tvTokenLogInfo,
            tvStayAndDirection = tvStayAndDirection,
            detailText = "SRK3 Trading GPS System | Stopped - Token Required"
        )
        updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)

        // Mode selection buttons
        btnModeOffice.setOnClickListener {
            selectedMode = "Office"
            tvSelectedMode.text = "Mode: Office"
            btnModeOffice.setBackgroundColor(Color.parseColor("#1565C0"))
            btnModeFamily.setBackgroundColor(Color.parseColor("#757575"))
        }

        btnModeFamily.setOnClickListener {
            selectedMode = "Family"
            tvSelectedMode.text = "Mode: Family"
            btnModeFamily.setBackgroundColor(Color.parseColor("#1565C0"))
            btnModeOffice.setBackgroundColor(Color.parseColor("#757575"))
        }

        // Generate fresh local token when tapping received token box
        tvReceivedToken.setOnClickListener {
            if (isTokenActivated) {
                Toast.makeText(
                    this,
                    "Disconnect first to remove current token and generate a new one",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            generatedToken = generateLocalToken(etDeviceId.text.toString())
            tvReceivedToken.text = generatedToken
            Toast.makeText(this, "New SRK3 local token generated", Toast.LENGTH_SHORT).show()
        }

        // Paste token from local generated token box or system clipboard
        btnPasteToken.setOnClickListener {
            if (isTokenActivated) {
                Toast.makeText(this, "Token already active. Disconnect to generate a new one", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (generatedToken.isBlank()) {
                generatedToken = generateLocalToken(etDeviceId.text.toString())
            }

            if (!usedTokens.contains(generatedToken)) {
                tvReceivedToken.text = generatedToken
                etToken.setText(generatedToken)
                Toast.makeText(this, "Local token pasted", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clipText.isNullOrEmpty() && !usedTokens.contains(clipText)) {
                    etToken.setText(clipText)
                    Toast.makeText(this, "Clipboard token pasted", Toast.LENGTH_SHORT).show()
                } else {
                    generatedToken = generateLocalToken(etDeviceId.text.toString())
                    tvReceivedToken.text = generatedToken
                    etToken.setText(generatedToken)
                    Toast.makeText(this, "Fresh single-use token generated & pasted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Activate single-use token, clear input box, and show GPS symbol
        btnVerifyToken.setOnClickListener {
            val tokenValue = etToken.text.toString().trim()
            if (tokenValue.isEmpty()) {
                Toast.makeText(this, "Please enter or paste a token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (usedTokens.contains(tokenValue)) {
                etToken.text.clear()
                Toast.makeText(this, "This token was already used. Generating a new token...", Toast.LENGTH_SHORT).show()
                generatedToken = generateLocalToken(etDeviceId.text.toString())
                tvReceivedToken.text = generatedToken
                return@setOnClickListener
            }

            // Consume token so it cannot be reused
            usedTokens.add(tokenValue)
            activeToken = tokenValue
            isTokenActivated = true
            generatedToken = ""

            // Clear token input & received token box after activation
            etToken.text.clear()
            tvReceivedToken.text = "SRK3 Active Session Token Locked"

            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            statusBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus.text = "📍 GPS: CONNECTED & ACTIVE"
            tvStatusDetail.text = "SRK3 Trading GPS System | Token Active ($selectedMode)"
            tvTokenLogInfo.text = "Token Audit: Single-use token activated at $currentTime"
            tvLastSync.text = "Last Sync: $currentTime"
            Toast.makeText(this, "Token activated and locked for single use", Toast.LENGTH_SHORT).show()
        }

        // Start tracking service
        btnStartTracking.setOnClickListener {
            val userName = etDeviceId.text.toString().trim().ifEmpty { "Shahid Farooqui" }
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            if (!isTokenActivated || activeToken.isNullOrBlank()) {
                val freshToken = generateLocalToken(userName)
                usedTokens.add(freshToken)
                activeToken = freshToken
                isTokenActivated = true
                generatedToken = ""
                etToken.text.clear()
                tvReceivedToken.text = "SRK3 Active Session Token Locked"
                tvTokenLogInfo.text = "Token Audit: Auto-activated at $currentTime"
            }

            val serviceIntent = Intent(this, LocationService::class.java).apply {
                putExtra("DEVICE_ID", userName)
                putExtra("SYSTEM_NAME", "SRK3 Trading GPS System")
                putExtra("TRACKING_MODE", selectedMode)
                putExtra("TOKEN", activeToken)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            statusBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus.text = "🛰️ GPS: CONNECTED & TRACKING"
            tvStatusDetail.text = "SRK3 Trading GPS System | $userName ($selectedMode)"
            tvLastSync.text = "Last Sync: $currentTime"
            updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)
        }

        // Disconnect / stop tracking: remove old token and generate a fresh one
        btnStopTracking.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))

            // Remove current active token completely
            activeToken = null
            isTokenActivated = false
            etToken.text.clear()

            // Generate a new token ready for the next connection
            generatedToken = generateLocalToken(etDeviceId.text.toString())
            tvReceivedToken.text = generatedToken

            setDisconnectedState(
                statusBanner = statusBanner,
                tvGpsStatus = tvGpsStatus,
                tvStatusDetail = tvStatusDetail,
                tvSelectedMode = tvSelectedMode,
                tvTokenLogInfo = tvTokenLogInfo,
                tvStayAndDirection = tvStayAndDirection,
                detailText = "SRK3 Trading GPS System | Disconnected - New Token Ready"
            )
            Toast.makeText(this, "Disconnected. Old token removed and new token generated.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateLocalToken(rawUserId: String): String {
        val cleanId = rawUserId.trim()
            .ifEmpty { "ShahidFarooqui" }
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(10)
            .uppercase(Locale.getDefault())
            .ifEmpty { "SHAHIDFAROOQUI" }

        val timePart = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
        var candidate: String
        do {
            val randomCode = Random.nextInt(1000, 9999)
            candidate = "SRK3-$cleanId-$timePart-$randomCode"
        } while (usedTokens.contains(candidate))

        return candidate
    }

    private fun setDisconnectedState(
        statusBanner: LinearLayout,
        tvGpsStatus: TextView,
        tvStatusDetail: TextView,
        tvSelectedMode: TextView,
        tvTokenLogInfo: TextView,
        tvStayAndDirection: TextView,
        detailText: String
    ) {
        statusBanner.setBackgroundColor(Color.parseColor("#D32F2F"))
        tvGpsStatus.text = "🚫📡 GPS: DISCONNECTED"
        tvStatusDetail.text = detailText
        tvSelectedMode.text = "Mode: $selectedMode"
        tvTokenLogInfo.text = "Token Audit: Ready for fresh SRK3 local token"
        tvStayAndDirection.text = "Direction / Route: Stopped | Dwell Time: 0m"
    }

    private fun updateBatteryAndInfo(tvBattery: TextView, tvStay: TextView) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryText = if (level in 0..100) "$level%" else "--%"
        tvBattery.text = "Battery: $batteryText | Stay Log: Active"
        tvStay.text = "Direction / Route: Monitoring ($selectedMode) | Dwell Time: 0m"
    }
}
