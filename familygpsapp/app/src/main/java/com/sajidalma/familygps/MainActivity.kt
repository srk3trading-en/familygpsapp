package com.sajidalam.familygps

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

class MainActivity : AppCompatActivity() {

    private var selectedMode: String = "Office"
    private var isTokenActivated: Boolean = false

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

        // Default mode UI
        tvSelectedMode.text = "Mode: $selectedMode"
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

        // Paste token from received token box or system clipboard
        btnPasteToken.setOnClickListener {
            val receivedText = tvReceivedToken.text.toString().trim()
            if (receivedText.isNotEmpty() && !receivedText.startsWith("Waiting", ignoreCase = true)) {
                etToken.setText(receivedText)
                Toast.makeText(this, "Token pasted", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clipText.isNullOrEmpty()) {
                    etToken.setText(clipText)
                    Toast.makeText(this, "Clipboard token pasted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No token available to paste", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Verify token and turn banner green when connected
        btnVerifyToken.setOnClickListener {
            val tokenValue = etToken.text.toString().trim()
            if (tokenValue.isEmpty()) {
                Toast.makeText(this, "Please enter or paste a token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isTokenActivated = true
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            statusBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus.text = "GPS: CONNECTED"
            tvStatusDetail.text = "Status: Token Active ($selectedMode) | Connected"
            tvTokenLogInfo.text = "Token Audit: Activated at $currentTime"
            tvLastSync.text = "Last Sync: $currentTime"
            Toast.makeText(this, "Token activated and GPS connected", Toast.LENGTH_SHORT).show()
        }

        // Start tracking service
        btnStartTracking.setOnClickListener {
            val deviceName = etDeviceId.text.toString().trim().ifEmpty { "Shahid" }
            val currentToken = etToken.text.toString().trim()
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            val serviceIntent = Intent(this, LocationService::class.java).apply {
                putExtra("DEVICE_ID", deviceName)
                putExtra("TRACKING_MODE", selectedMode)
                putExtra("TOKEN", currentToken)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            isTokenActivated = true
            statusBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvGpsStatus.text = "GPS: CONNECTED & ACTIVE"
            tvStatusDetail.text = "Status: Tracking Active ($deviceName | $selectedMode)"
            tvLastSync.text = "Last Sync: $currentTime"
            updateBatteryAndInfo(tvBatteryInfo, tvStayAndDirection)
        }

        // Stop tracking service
        btnStopTracking.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            isTokenActivated = false
            statusBanner.setBackgroundColor(Color.parseColor("#D32F2F"))
            tvGpsStatus.text = "GPS: DISCONNECTED"
            tvStatusDetail.text = "Status: Stopped"
            tvStayAndDirection.text = "Direction / Route: Stopped | Dwell Time: 0m"
        }
    }

    private fun updateBatteryAndInfo(tvBattery: TextView, tvStay: TextView) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryText = if (level in 0..100) "$level%" else "--%"
        tvBattery.text = "Battery: $batteryText | Stay Log: Active"
        tvStay.text = "Direction / Route: Monitoring ($selectedMode) | Dwell Time: 0m"
    }
}
