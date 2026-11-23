package com.sajidalam.familygps

import com.sajidalam.familygps.R
import com.sajidalam.familygps.LocationService

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

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            100
        )

        tvReceivedToken.text = "Enter ID first to generate token"

        setDisconnectedState(
            statusBanner = statusBanner,
            tvGpsStatus = tvGpsStatus,
            tvStatusDetail = tvStatusDetail,
            tvSelectedMode = tvSelectedMode,
            tvTokenLogInfo = tvTokenLogInfo,
            tvStayAndDirection = tvStayAndDirection,
            detailText = "SRK 3 Trading GPS System | Please enter the ID"
        )
        updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)

        btnModeOffice.setOnClickListener {
            selectedMode = "Office"
            tvSelectedMode.text = "Mode: Office"
            btnModeOffice.setBackgroundColor(Color.parseColor("#1565C0"))
            btnModeFamily.setBackgroundColor(Color.parseColor("#757575"))
            val rawId = etDeviceId.text.toString().trim()
            if (rawId.isNotEmpty()) {
                tvStatusDetail.text = "${resolveDisplayLabel(rawId)} | SRK 3 Trading GPS System"
            }
            updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)
        }

        btnModeFamily.setOnClickListener {
            selectedMode = "Family"
            tvSelectedMode.text = "Mode: Home Family"
            btnModeFamily.setBackgroundColor(Color.parseColor("#1565C0"))
            btnModeOffice.setBackgroundColor(Color.parseColor("#757575"))
            val rawId = etDeviceId.text.toString().trim()
            if (rawId.isNotEmpty()) {
                tvStatusDetail.text = "${resolveDisplayLabel(rawId)} | SRK 3 Trading GPS System"
            }
            updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)
        }

        tvReceivedToken.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, tvStatusDetail, tvTokenLogInfo)) {
                return@setOnClickListener
            }
            if (isTokenActivated) {
                tvTokenLogInfo.text = "Token Audit: Disconnect first to generate a new token"
                return@setOnClickListener
            }
            generatedToken = generateLocalToken(rawId)
            tvReceivedToken.text = generatedToken
            tvTokenLogInfo.text = "Token Audit: New SRK 3 Trading local token ready"
        }

        btnPasteToken.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, tvStatusDetail, tvTokenLogInfo)) {
                return@setOnClickListener
            }
            if (isTokenActivated) {
                tvTokenLogInfo.text = "Token Audit: Token already active. Disconnect to reset"
                return@setOnClickListener
            }

            if (generatedToken.isBlank()) {
                generatedToken = generateLocalToken(rawId)
            }

            if (!usedTokens.contains(generatedToken)) {
                tvReceivedToken.text = generatedToken
                etToken.setText(generatedToken)
                tvTokenLogInfo.text = "Token Audit: Local token pasted"
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clipText.isNullOrEmpty() && !usedTokens.contains(clipText)) {
                    etToken.setText(clipText)
                    tvTokenLogInfo.text = "Token Audit: Clipboard token pasted"
                } else {
                    generatedToken = generateLocalToken(rawId)
                    tvReceivedToken.text = generatedToken
                    etToken.setText(generatedToken)
                    tvTokenLogInfo.text = "Token Audit: Fresh single-use token generated & pasted"
                }
            }
        }

        btnVerifyToken.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, tvStatusDetail, tvTokenLogInfo)) {
                return@setOnClickListener
            }

            val tokenValue = etToken.text.toString().trim()
            if (tokenValue.isEmpty()) {
                etToken.error = "Enter or paste token"
                tvTokenLogInfo.text = "Token Audit: Please enter or paste a token"
                return@setOnClickListener
            }
            etToken.error = null

            if (usedTokens.contains(tokenValue)) {
                etToken.text.clear()
                generatedToken = generateLocalToken(rawId)
                tvReceivedToken.text = generatedToken
                tvTokenLogInfo.text = "Token Audit: Already used. New token generated"
                return@setOnClickListener
            }

            usedTokens.add(tokenValue)
            activeToken = tokenValue
            isTokenActivated = true
            generatedToken = ""

            etToken.text.clear()
            tvReceivedToken.text = "SRK 3 Trading Active Session Token Locked"

            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val label = resolveDisplayLabel(rawId)
            statusBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus.text = "📍 GPS: CONNECTED & ACTIVE"
            tvStatusDetail.text = "$label | Token Active"
            tvTokenLogInfo.text = "Token Audit: Activated at $currentTime (Single-use)"
            tvLastSync.text = "Last Sync: $currentTime"
        }

        btnStartTracking.setOnClickListener {
            val rawId = etDeviceId.text.toString().trim()
            if (!requireDeviceId(etDeviceId, tvStatusDetail, tvTokenLogInfo)) {
                return@setOnClickListener
            }

            val isHomeFamily = selectedMode == "Family" ||
                rawId.contains("home", ignoreCase = true) ||
                rawId.contains("family", ignoreCase = true)
            val effectiveMode = if (isHomeFamily) "Home Family" else "Office"
            val displayLabel = resolveDisplayLabel(rawId)
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            if (!isTokenActivated || activeToken.isNullOrBlank()) {
                val freshToken = generateLocalToken(rawId)
                usedTokens.add(freshToken)
                activeToken = freshToken
                isTokenActivated = true
                generatedToken = ""
                etToken.text.clear()
                tvReceivedToken.text = "SRK 3 Trading Active Session Token Locked"
                tvTokenLogInfo.text = "Token Audit: Auto-activated at $currentTime"
            }

            val serviceIntent = Intent(this, LocationService::class.java).apply {
                putExtra("DEVICE_ID", rawId)
                putExtra("SYSTEM_NAME", "SRK 3 Trading GPS System")
                putExtra("TRACKING_MODE", effectiveMode)
                putExtra("TOKEN", activeToken)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            statusBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus.text = "🛰️ GPS: CONNECTED & TRACKING"
            tvStatusDetail.text = displayLabel
            tvLastSync.text = "Last Sync: $currentTime"
            updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)
        }

        btnStopTracking.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))

            activeToken = null
            isTokenActivated = false
            etToken.text.clear()

            val rawId = etDeviceId.text.toString().trim()
            if (rawId.isNotEmpty()) {
                generatedToken = generateLocalToken(rawId)
                tvReceivedToken.text = generatedToken
            } else {
                generatedToken = ""
                tvReceivedToken.text = "Enter ID first to generate token"
            }

            val detailText = if (rawId.isNotEmpty()) {
                "${resolveDisplayLabel(rawId)} | Disconnected - New Token Ready"
            } else {
                "SRK 3 Trading GPS System | Please enter the ID"
            }

            setDisconnectedState(
                statusBanner = statusBanner,
                tvGpsStatus = tvGpsStatus,
                tvStatusDetail = tvStatusDetail,
                tvSelectedMode = tvSelectedMode,
                tvTokenLogInfo = tvTokenLogInfo,
                tvStayAndDirection = tvStayAndDirection,
                detailText = detailText
            )
        }
    }

    private fun requireDeviceId(
        etDeviceId: EditText,
        tvStatusDetail: TextView,
        tvTokenLogInfo: TextView
    ): Boolean {
        val rawId = etDeviceId.text.toString().trim()
        if (rawId.isEmpty()) {
            etDeviceId.error = "Please enter the ID"
            etDeviceId.requestFocus()
            tvStatusDetail.text = "SRK 3 Trading GPS System | Please enter the ID"
            tvTokenLogInfo.text = "Token Audit: Device / User ID is required"
            return false
        }
        etDeviceId.error = null
        return true
    }

    private fun resolveDisplayLabel(rawInput: String): String {
        val cleanedInput = rawInput.trim()
        val isHomeFamily = selectedMode == "Family" ||
            cleanedInput.contains("home", ignoreCase = true) ||
            cleanedInput.contains("family", ignoreCase = true)

        val personName = cleanedInput
            .replace(Regex("\\b(home|family|office)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { cleanedInput }

        val areaLabel = if (isHomeFamily) "Home Family" else "Office"
        return "$personName - $areaLabel"
    }

    private fun generateLocalToken(rawUserId: String): String {
        val cleanId = rawUserId.trim()
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(10)
            .uppercase(Locale.getDefault())
            .ifEmpty { "USERID" }

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
        tvSelectedMode.text = if (selectedMode == "Family") "Mode: Home Family" else "Mode: Office"
        tvTokenLogInfo.text = "Token Audit: Ready for fresh SRK 3 Trading local token"
        tvStayAndDirection.text = "Direction / Route: Stopped | Dwell Time: 0m"
    }

    private fun updateBatteryAndInfo(tvBattery: TextView, tvStay: TextView) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryText = if (level in 0..100) "$level%" else "--%"
        val modeLabel = if (selectedMode == "Family") "Home Family" else "Office"
        tvBattery.text = "Battery: $batteryText | Stay Log: Active"
        tvStay.text = "Direction / Route: Monitoring ($modeLabel) | Dwell Time: 0m"
    }
}
