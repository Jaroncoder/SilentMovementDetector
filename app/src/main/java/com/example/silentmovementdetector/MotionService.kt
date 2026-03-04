package com.example.silentmovementdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class MotionService : LifecycleService(), SensorEventListener {

    private val CHANNEL_ID = "MotionDetectorChannel"
    private val NOTIF_ID = 1
    private lateinit var notificationManager: NotificationManager
    private lateinit var vibrator: Vibrator
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var cameraExecutor: ExecutorService
    private var previousBitmap: Bitmap? = null
    private var motionDetected = false
    private var lastCaptureTime = 0L
    private var consecutiveMotionFrames = 0
    private var consecutiveStillFrames = 0
    private var cameraProvider: ProcessCameraProvider? = null

    // Accelerometer vars
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var accelInitialized = false
    private var lastAccelAlertTime = 0L
    private val ACCEL_THRESHOLD = 1.2f
    private val ACCEL_COOLDOWN = 3000L

    companion object {
        var sharedPreviewInstance: Preview? = null
        var isAppInForeground = false
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(android.os.VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        cameraExecutor = Executors.newSingleThreadExecutor()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Monitoring for motion..."))

        // Always start accelerometer regardless
        accelerometer?.let {
            sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_GAME
            )
        }

        // Start camera for foreground detection
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    // Only analyze if app is in foreground
                    if (isAppInForeground) {
                        analyzeImage(imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }

                val preview = Preview.Builder().build()
                sharedPreviewInstance = preview

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                val readyIntent = Intent("CAMERA_READY")
                sendBroadcast(readyIntent)
                Log.d("MotionService", "Camera started")

            } catch (e: Exception) {
                Log.e("MotionService", "Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val currentBitmap = imageProxyToBitmap(imageProxy) ?: run {
            imageProxy.close()
            return
        }

        val prev = previousBitmap
        if (prev != null) {
            val diff = calculateDifference(prev, currentBitmap)
            Log.d("MotionService", "Diff: $diff")

            if (diff > 8.0) {
                consecutiveMotionFrames++
                consecutiveStillFrames = 0
                if (consecutiveMotionFrames >= 2) {
                    handleMotionDetected(currentBitmap)
                }
            } else {
                consecutiveStillFrames++
                consecutiveMotionFrames = 0
                if (consecutiveStillFrames >= 5) {
                    handleNoMotion()
                }
            }
        }

        previousBitmap = currentBitmap
        imageProxy.close()
    }

    private fun calculateDifference(bmp1: Bitmap, bmp2: Bitmap): Double {
        val sampleWidth = 80
        val sampleHeight = 80
        var totalDiff = 0.0
        var count = 0
        val w1 = bmp1.width
        val h1 = bmp1.height
        val w2 = bmp2.width
        val h2 = bmp2.height

        for (x in 0 until sampleWidth) {
            for (y in 0 until sampleHeight) {
                val px1 = (x.toFloat() / sampleWidth * w1).toInt().coerceIn(0, w1 - 1)
                val py1 = (y.toFloat() / sampleHeight * h1).toInt().coerceIn(0, h1 - 1)
                val px2 = (x.toFloat() / sampleWidth * w2).toInt().coerceIn(0, w2 - 1)
                val py2 = (y.toFloat() / sampleHeight * h2).toInt().coerceIn(0, h2 - 1)

                val p1 = bmp1.getPixel(px1, py1)
                val p2 = bmp2.getPixel(px2, py2)

                val r1 = (p1 shr 16) and 0xFF
                val g1 = (p1 shr 8) and 0xFF
                val b1 = p1 and 0xFF
                val r2 = (p2 shr 16) and 0xFF
                val g2 = (p2 shr 8) and 0xFF
                val b2 = p2 and 0xFF

                totalDiff += abs(r1 - r2) * 0.3 +
                        abs(g1 - g2) * 0.5 +
                        abs(b1 - b2) * 0.2
                count++
            }
        }
        return if (count > 0) totalDiff / count else 0.0
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer: ByteBuffer = imageProxy.planes[0].buffer
            val uBuffer: ByteBuffer = imageProxy.planes[1].buffer
            val vBuffer: ByteBuffer = imageProxy.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = YuvImage(
                nv21, ImageFormat.NV21,
                imageProxy.width, imageProxy.height, null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height), 85, out
            )
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun handleMotionDetected(bitmap: Bitmap) {
        if (!motionDetected) {
            motionDetected = true
            forceVibrate()
            sendMotionNotification("Camera detected motion!")
            Log.d("MotionService", "🚨 Camera motion detected!")
        }
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime > 2000) {
            lastCaptureTime = now
            val path = saveBitmap(bitmap)
            broadcastMotion(true, path)
        }
    }

    private fun handleNoMotion() {
        if (motionDetected) {
            motionDetected = false
            vibrator.cancel()
            broadcastMotion(false, "")
        }
    }

    // ─── ACCELEROMETER BACKGROUND DETECTION ───────────────────────────────
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!accelInitialized) {
            lastX = x; lastY = y; lastZ = z
            accelInitialized = true
            return
        }

        val deltaX = abs(x - lastX)
        val deltaY = abs(y - lastY)
        val deltaZ = abs(z - lastZ)

        lastX = x; lastY = y; lastZ = z

        val movement = (deltaX + deltaY + deltaZ) / 3f

        // Only use accelerometer when app is in BACKGROUND
        if (!isAppInForeground) {
            val now = System.currentTimeMillis()
            if (movement > ACCEL_THRESHOLD && now - lastAccelAlertTime > ACCEL_COOLDOWN) {
                lastAccelAlertTime = now
                Log.d("MotionService", "📳 Background movement: $movement")
                forceVibrate()
                sendMotionNotification("Movement detected while monitoring!")
                broadcastMotion(true, "")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    // ──────────────────────────────────────────────────────────────────────

    private fun forceVibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                val vib = vm.defaultVibrator
                vib.cancel()
                vib.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 300, 100, 300, 100, 300),
                        intArrayOf(0, 255, 0, 255, 0, 255), -1
                    )
                )
            } else {
                vibrator.cancel()
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        1000, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("MotionService", "Vibration error: ${e.message}")
        }
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        return try {
            val timeStamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.getDefault()
            ).format(Date())
            val dir = File(getExternalFilesDir(null), "MotionCaptures")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "MOTION_$timeStamp.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d("MotionService", "✅ Saved: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    private fun sendMotionNotification(message: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚨 Motion Detected!")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(2, notification)
    }

    private fun broadcastMotion(detected: Boolean, path: String) {
        val intent = Intent("MOTION_UPDATE")
        intent.putExtra("motion_detected", detected)
        intent.putExtra("image_path", path)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Motion Detector",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Sentinel motion detection"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("👁 Sentinel Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        vibrator.cancel()
        sensorManager.unregisterListener(this)
        cameraProvider?.unbindAll()
        previousBitmap = null
        sharedPreviewInstance = null
    }
}