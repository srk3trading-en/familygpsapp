package com.sajidalam.familygps

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etDeviceId = findViewById<EditText>(R.id.etDeviceId)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        // Permissions mangna
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            100
        )

        btnStart.setOnClickListener {
            val deviceName = etDeviceId.text.toString().ifEmpty { "Shahid" }
            val serviceIntent = Intent(this, LocationService::class.java).apply {
                putExtra("DEVICE_ID", deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            tvStatus.text = "Status: Tracking Active ($deviceName)"
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            tvStatus.text = "Status: Stopped"
        }
    }
}