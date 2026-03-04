package com.example.silentmovementdetector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.silentmovementdetector.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSION_REQUEST_CODE = 100
    private var uptimeSeconds = 0
    private var uptimeHandler: Handler? = null
    private var isRunning = false
    private var cameraProvider: ProcessCameraProvider? = null

    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                uptimeSeconds++
                val mins = uptimeSeconds / 60
                val secs = uptimeSeconds % 60
                binding.tvUptime.text = String.format("%02d:%02d", mins, secs)
                uptimeHandler?.postDelayed(this, 1000)
            }
        }
    }

    private val motionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "MOTION_UPDATE" -> {
                    val detected = intent.getBooleanExtra("motion_detected", false)
                    val imagePath = intent.getStringExtra("image_path") ?: ""
                    Log.d("MainActivity", "Motion detected: $detected")
                    if (detected) {
                        runOnUiThread {
                            binding.tvMotionAlert.text = "⚠ MOTION!"
                            binding.tvLog.text = "📸 Captured: ...${imagePath.takeLast(28)}"
                            binding.statusDot.setBackgroundResource(R.drawable.dot_alert)
                            binding.tvMotionOverlay.visibility = View.VISIBLE
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            binding.tvMotionAlert.text = ""
                            binding.tvMotionOverlay.visibility = View.GONE
                            if (isRunning) binding.statusDot.setBackgroundResource(R.drawable.dot_active)
                        }, 3000)
                    }
                }
                "CAMERA_READY" -> {
                    runOnUiThread { startLiveFeed() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            if (checkPermissions()) startDetection() else requestPermissions()
        }
        binding.btnStop.setOnClickListener { stopDetection() }
    }

    private fun startLiveFeed() {
        try {
            val servicePreview = MotionService.sharedPreviewInstance
            if (servicePreview != null) {
                servicePreview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                runOnUiThread {
                    binding.cameraOffOverlay.visibility = View.GONE
                    binding.tvLiveBadge.visibility = View.VISIBLE
                    startLiveBadgeBlink()
                }
                Log.d("MainActivity", "Live feed attached!")
            } else {
                // Not ready yet, retry in 1 second
                Handler(Looper.getMainLooper()).postDelayed({
                    startLiveFeed()
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Live feed error: ${e.message}")
        }
    }

    private fun stopLiveFeed() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Stop feed error: ${e.message}")
        }
        binding.cameraOffOverlay.visibility = View.VISIBLE
        binding.tvLiveBadge.visibility = View.GONE
        binding.tvLiveBadge.clearAnimation()
    }

    private fun startLiveBadgeBlink() {
        val blink = AlphaAnimation(1f, 0.2f).apply {
            duration = 600
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        binding.tvLiveBadge.startAnimation(blink)
    }

    private fun startDetection() {
        // Reset counter on fresh start

        val intent = Intent(this, MotionService::class.java)
        ContextCompat.startForegroundService(this, intent)

        binding.tvStatus.text = "System Active"
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        binding.statusDot.setBackgroundResource(R.drawable.dot_active)
        binding.tvLog.text = "Monitoring in progress..."
        isRunning = true
        uptimeSeconds = 0
        uptimeHandler = Handler(Looper.getMainLooper())
        uptimeHandler?.postDelayed(uptimeRunnable, 1000)
        startBlinkAnimation()

        // Try starting live feed immediately
        Handler(Looper.getMainLooper()).postDelayed({
            startLiveFeed()
        }, 1500)

        Toast.makeText(this, "Sentinel Activated ✅", Toast.LENGTH_SHORT).show()
    }

    private fun stopDetection() {
        val intent = Intent(this, MotionService::class.java)
        stopService(intent)

        binding.tvStatus.text = "System Idle"
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        binding.statusDot.setBackgroundResource(R.drawable.dot_inactive)
        binding.tvMotionAlert.text = ""
        binding.tvLog.text = "Awaiting activation..."
        binding.tvUptime.text = "00:00"
        binding.tvMotionOverlay.visibility = View.GONE
        isRunning = false
        uptimeHandler?.removeCallbacks(uptimeRunnable)
        binding.statusDot.clearAnimation()
        stopLiveFeed()

        Toast.makeText(this, "Sentinel Deactivated 🔴", Toast.LENGTH_SHORT).show()
    }

    private fun startBlinkAnimation() {
        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 800
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        binding.statusDot.startAnimation(blink)
    }

    override fun onDestroy() {
        super.onDestroy()
        uptimeHandler?.removeCallbacks(uptimeRunnable)
    }

    private fun checkPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
        return camera && notify
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            startDetection()
        else
            Toast.makeText(this, "Permissions required!", Toast.LENGTH_LONG).show()
    }
    override fun onResume() {
        super.onResume()
        MotionService.isAppInForeground = true  // ← add this
        val filter = IntentFilter().apply {
            addAction("MOTION_UPDATE")
            addAction("CAMERA_READY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(motionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(motionReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        MotionService.isAppInForeground = false  // ← add this
        try { unregisterReceiver(motionReceiver) } catch (e: Exception) { }
    }
}