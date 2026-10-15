package com.bridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceInfoText: TextView

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                startBridgeService()
            } else {
                statusText.text = "Permissions required to run Bridge"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "Bridge"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            text = "Initializing..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        deviceInfoText = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
        }

        layout.addView(titleText)
        layout.addView(statusText)
        layout.addView(deviceInfoText)

        setContentView(layout)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startBridgeService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startBridgeService() {
        statusText.text = "Starting Bridge service..."
        val intent = Intent(this, BridgeService::class.java)
        startForegroundService(intent)
        statusText.text = "Bridge active - advertising BLE"
        deviceInfoText.text = "Device: ${Build.MODEL}"
    }
}
