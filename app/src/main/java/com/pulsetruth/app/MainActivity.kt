package com.pulsetruth.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var tvFingerStatus: TextView
    private lateinit var tvBpm: TextView
    private lateinit var tvBaseline: TextView
    private lateinit var tvDelta: TextView
    private lateinit var tvStressPct: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var stressBar: ProgressBar
    private lateinit var pulseGraph: PulseGraphView
    private lateinit var btnSoundToggle: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var toneGenerator: ToneGenerator? = null
    private var soundEnabled = true

    // PPG Signal Processing State
    private var isFingerPresent = false
    private val rawWindow = ArrayDeque<Float>(25)
    private val smoothWindow = ArrayDeque<Float>(5)
    private var prevFiltered = 0f
    private var prevPrevFiltered = 0f
    private var lastPeakTime = 0L
    private val ibiList = ArrayDeque<Long>(8)
    private var currentBpm = 0

    // Calibration & Lie Detection State
    private val calibrationSamples = mutableListOf<Int>()
    private var baselineBpm = 0
    private var isCalibrated = false
    private var recentMaxBpm = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        previewView = findViewById(R.id.previewView)
        tvFingerStatus = findViewById(R.id.tvFingerStatus)
        tvBpm = findViewById(R.id.tvBpm)
        tvBaseline = findViewById(R.id.tvBaseline)
        tvDelta = findViewById(R.id.tvDelta)
        tvStressPct = findViewById(R.id.tvStressPct)
        tvVerdict = findViewById(R.id.tvVerdict)
        stressBar = findViewById(R.id.stressBar)
        pulseGraph = findViewById(R.id.pulseGraph)
        btnSoundToggle = findViewById(R.id.btnSoundToggle)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
        } catch (_: Exception) {
        }

        btnSoundToggle.setOnClickListener {
            soundEnabled = !soundEnabled
            btnSoundToggle.text = if (soundEnabled) "🔊 Beep ON" else "🔇 Beep OFF"
        }

        findViewById<Button>(R.id.btnRecalibrate).setOnClickListener {
            resetSession()
            Toast.makeText(this, "Scanner reset — calibrating fresh baseline", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCheckVerdict).setOnClickListener {
            evaluateAnswerVerdict()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission needed for PPG Heartbeat Scan", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                // Turn ON Rear Flash (Torch) to illuminate fingertip blood flow
                camera?.cameraControl?.enableTorch(true)
            } catch (e: Exception) {
                runOnUiThread {
                    tvFingerStatus.text = "Camera Error: ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }

        try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            val width = image.width
            val height = image.height

            val startX = width / 4
            val endX = (width * 3) / 4
            val startY = height / 4
            val endY = (height * 3) / 4

            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var count = 0

            val step = 6
            var yIdx = startY
            while (yIdx < endY) {
                val yRowOff = yIdx * yRowStride
                val uvRow = yIdx / 2
                val uRowOff = uvRow * uRowStride
                val vRowOff = uvRow * vRowStride

                var xIdx = startX
                while (xIdx < endX) {
                    val yVal = (yBuffer.get(yRowOff + xIdx).toInt() and 0xFF).toFloat()
                    val uvCol = xIdx / 2
                    val uVal = (uBuffer.get(uRowOff + uvCol * uPixelStride).toInt() and 0xFF) - 128f
                    val vVal = (vBuffer.get(vRowOff + uvCol * vPixelStride).toInt() and 0xFF) - 128f

                    val r = (yVal + 1.402f * vVal).coerceIn(0f, 255f)
                    val g = (yVal - 0.344136f * uVal - 0.714136f * vVal).coerceIn(0f, 255f)
                    val b = (yVal + 1.772f * uVal).coerceIn(0f, 255f)

                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                    xIdx += step
                }
                yIdx += step
            }

            if (count > 0) {
                val avgR = (sumR / count).toFloat()
                val avgG = (sumG / count).toFloat()
                val avgB = (sumB / count).toFloat()

                // Finger illuminated by flash produces strong bright Red dominance
                val fingerDetected = avgR > 135f && avgR > avgG * 1.35f && avgR > avgB * 1.35f

                if (fingerDetected) {
                    if (!isFingerPresent) {
                        isFingerPresent = true
                        runOnUiThread {
                            tvFingerStatus.text = "🔴 FINGER LOCKED — READING PULSE..."
                            tvFingerStatus.setTextColor(Color.parseColor("#00E676"))
                        }
                    }
                    handleRedSample(avgR, System.currentTimeMillis())
                } else {
                    if (isFingerPresent) {
                        isFingerPresent = false
                        clearSignalBuffers()
                        runOnUiThread {
                            tvFingerStatus.text = "👆 PUT FINGER ON REAR CAMERA + FLASH"
                            tvFingerStatus.setTextColor(Color.parseColor("#FFEB3B"))
                            tvBpm.text = "--"
                            pulseGraph.clear()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    private fun handleRedSample(avgR: Float, nowMs: Long) {
        // 1. Moving baseline subtraction (high-pass filter to remove DC drift)
        if (rawWindow.size >= 25) rawWindow.removeFirst()
        rawWindow.addLast(avgR)
        val baseline = rawWindow.average().toFloat()
        val acSignal = avgR - baseline

        // 2. Short smoothing (low-pass filter to reduce camera noise)
        if (smoothWindow.size >= 4) smoothWindow.removeFirst()
        smoothWindow.addLast(acSignal)
        val filtered = smoothWindow.average().toFloat()

        // Invert so systolic blood surge points upward on graph
        val plotVal = -filtered
        pulseGraph.addSample(plotVal)

        // 3. Peak Detection (local maximum with refractory period 360ms..1400ms -> 43..166 BPM)
        val isLocalPeak = prevFiltered > prevPrevFiltered && prevFiltered > plotVal && prevFiltered > 0.18f
        if (isLocalPeak) {
            val deltaMs = nowMs - lastPeakTime
            if (lastPeakTime > 0L && deltaMs in 360L..1400L) {
                if (ibiList.size >= 8) ibiList.removeFirst()
                ibiList.addLast(deltaMs)

                if (soundEnabled) {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
                }

                if (ibiList.size >= 3) {
                    val sorted = ibiList.sorted()
                    val medianIbi = sorted[sorted.size / 2]
                    val bpm = (60_000.0 / medianIbi).roundToInt().coerceIn(45, 165)
                    currentBpm = bpm
                    onNewBpmMeasured(bpm)
                }
            }
            lastPeakTime = nowMs
        }

        prevPrevFiltered = prevFiltered
        prevFiltered = plotVal
    }

    private fun onNewBpmMeasured(bpm: Int) {
        if (!isCalibrated) {
            calibrationSamples.add(bpm)
            val pct = ((calibrationSamples.size / 7f) * 100f).toInt().coerceAtMost(100)
            runOnUiThread {
                tvBpm.text = bpm.toString()
                tvVerdict.text = "Calibrating Baseline Pulse... $pct%
(Keep finger still & relax)"
                tvVerdict.setTextColor(Color.parseColor("#FFEB3B"))
                pulseGraph.setWaveColor(Color.parseColor("#FFEB3B"))
            }
            if (calibrationSamples.size >= 7) {
                baselineBpm = calibrationSamples.sorted()[calibrationSamples.size / 2]
                recentMaxBpm = baselineBpm
                isCalibrated = true
                runOnUiThread {
                    tvBaseline.text = "$baselineBpm BPM"
                    tvVerdict.text = "✅ BASELINE LOCKED ($baselineBpm BPM)
Ask your friend a tough question & tap ANALYZE!"
                    tvVerdict.setTextColor(Color.parseColor("#00E676"))
                    pulseGraph.setWaveColor(Color.parseColor("#00E676"))
                }
            }
            return
        }

        if (bpm > recentMaxBpm) {
            recentMaxBpm = bpm
        } else {
            // Slowly decay peak tracker so it reflects the current question window
            recentMaxBpm = ((recentMaxBpm * 4 + bpm) / 5)
        }

        val delta = bpm - baselineBpm
        val stress = ((delta.coerceAtLeast(0) / 14f) * 100f).toInt().coerceIn(5, 100)

        runOnUiThread {
            tvBpm.text = bpm.toString()
            val sign = if (delta >= 0) "+" else ""
            tvDelta.text = "$sign$delta BPM"
            tvStressPct.text = "$stress%"
            stressBar.progress = stress

            if (delta >= 8) {
                tvBpm.setTextColor(Color.parseColor("#FF1744"))
                tvDelta.setTextColor(Color.parseColor("#FF1744"))
                tvStressPct.setTextColor(Color.parseColor("#FF1744"))
                pulseGraph.setWaveColor(Color.parseColor("#FF1744"))
                tvVerdict.text = "⚠️ STRESS SPIKE DETECTED! ($sign$delta BPM)"
                tvVerdict.setTextColor(Color.parseColor("#FF1744"))
            } else {
                tvBpm.setTextColor(Color.parseColor("#00E676"))
                tvDelta.setTextColor(Color.parseColor("#00E676"))
                tvStressPct.setTextColor(Color.parseColor("#00E676"))
                pulseGraph.setWaveColor(Color.parseColor("#00E676"))
            }
        }
    }

    private fun evaluateAnswerVerdict() {
        if (!isFingerPresent) {
            Toast.makeText(this, "Pehle dost ki ungli Rear Camera par rakho!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isCalibrated) {
            Toast.makeText(this, "Baseline calibrate hone do (5 sec)!", Toast.LENGTH_SHORT).show()
            return
        }

        val spike = (maxOf(currentBpm, recentMaxBpm) - baselineBpm)
        if (spike >= 7) {
            // LIE DETECTED
            rootContainer.setBackgroundColor(Color.parseColor("#3B0810"))
            tvVerdict.text = "🚨 LIE DETECTED!
Heart rate spiked +$spike BPM above baseline!"
            tvVerdict.setTextColor(Color.parseColor("#FF5252"))
            pulseGraph.setWaveColor(Color.parseColor("#FF1744"))
            vibrateAlert(true)
            if (soundEnabled) toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
        } else {
            // TRUTH DETECTED
            rootContainer.setBackgroundColor(Color.parseColor("#082616"))
            tvVerdict.text = "✅ TRUTH DETECTED!
Pulse remained calm (${if (spike >= 0) "+$spike" else "$spike"} BPM)"
            tvVerdict.setTextColor(Color.parseColor("#00E676"))
            pulseGraph.setWaveColor(Color.parseColor("#00E676"))
            vibrateAlert(false)
            if (soundEnabled) toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
        }

        // Reset spike tracker for next question
        recentMaxBpm = currentBpm
    }

    private fun vibrateAlert(isLie: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (isLie) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 300), -1))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun clearSignalBuffers() {
        rawWindow.clear()
        smoothWindow.clear()
        ibiList.clear()
        prevFiltered = 0f
        prevPrevFiltered = 0f
        lastPeakTime = 0L
    }

    private fun resetSession() {
        clearSignalBuffers()
        calibrationSamples.clear()
        baselineBpm = 0
        recentMaxBpm = 0
        currentBpm = 0
        isCalibrated = false
        rootContainer.setBackgroundColor(Color.parseColor("#090B10"))
        tvBpm.text = "--"
        tvBaseline.text = "-- BPM"
        tvDelta.text = "Waiting..."
        tvStressPct.text = "0%"
        stressBar.progress = 0
        tvVerdict.text = "Step 1: Place friend's finger on Rear Camera"
        tvVerdict.setTextColor(Color.parseColor("#DDDDDD"))
        pulseGraph.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        camera?.cameraControl?.enableTorch(false)
        toneGenerator?.release()
        cameraExecutor.shutdown()
    }
}
