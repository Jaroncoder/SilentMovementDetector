package com.example.silentmovementdetector

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class LiveFeedActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        // Overlay with back button
        val rootLayout = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // Label at top
        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 60, 40, 20)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = android.widget.TextView(this).apply {
            text = "← BACK"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = 30f
            }
            setPadding(30, 15, 30, 15)
            setOnClickListener { finish() }
        }

        val titleText = android.widget.TextView(this).apply {
            text = "  LIVE FEED"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        // Live indicator
        val liveBadge = android.widget.TextView(this).apply {
            text = "⬤ LIVE"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#FF4757"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22FF4757)
                cornerRadius = 20f
            }
            setPadding(20, 10, 20, 10)
        }

        topBar.addView(backBtn)
        topBar.addView(titleText)
        topBar.addView(liveBadge)

        // Bottom info bar
        val bottomBar = android.widget.TextView(this).apply {
            text = "🔍 Motion detection running in background"
            textSize = 13f
            setTextColor(0x88FFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xCC0A0E1F.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        }

        rootLayout.addView(previewView)
        rootLayout.addView(topBar)
        rootLayout.addView(bottomBar)
        setContentView(rootLayout)

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } catch (e: Exception) {
                Log.e("LiveFeed", "Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
}