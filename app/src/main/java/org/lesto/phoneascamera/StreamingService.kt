package org.lesto.phoneascamera

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodecInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.udp.UdpCamera2
import com.pedro.library.view.OpenGlView

class StreamingService : Service() {

    companion object {
        var instance: StreamingService? = null
        const val ACTION_SWITCH_CAMERA = "org.lesto.phoneascamera.SWITCH_CAMERA"
        const val EXTRA_CAMERA_ID = "camera_id"
        var onStopped: (() -> Unit)? = null
        var onStarted: (() -> Unit)? = null
        val serverIp = "192.168.178.25"  // replace with receiver IP
        val port = 5000
    }

    private lateinit var udpCamera2: UdpCamera2
    private var wakeLock: PowerManager.WakeLock? = null

    fun switchCamera(cameraId: String) {
        udpCamera2.switchCamera(cameraId)
    }

    fun setPreview(view: OpenGlView?) {
        //TODO: BROKEN
//        val wasStreaming = udpCamera2.isStreaming
//        if (view != null) {
//            udpCamera2.replaceView(view)
//        } else {
//            udpCamera2.replaceView(this) // back to internal windowmanager view
//        }
//        if (wasStreaming) {
//            Thread {
//                udpCamera2.startStream("udp://$serverIp:$port")
//            }.start()
//        }
    }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() {
            Log.d("UDP", "Connected")
            onStarted?.invoke()
        }
        override fun onConnectionFailed(reason: String) {
            Log.e("UDP", "Failed: $reason")
//            runOnUiThread {
//                udpCamera2.stopStream()
//                running = false
//                button.text = "START STREAM"
//            }
            stopSelf()
        }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() {
            Log.d("UDP", "Disconnected")
        }
        override fun onAuthError() {}
        override fun onAuthSuccess() {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Create a persistent notification
//        /val icon = IconCompat.createWithResource(this, R.drawable.ic_camera)
        val notification = NotificationCompat.Builder(this, "STREAM_CHANNEL")
            .setContentTitle("Streaming")
            .setContentText("Camera is streaming in background")
            .setSmallIcon(R.drawable.ic_camera)
            .build()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
            else -> startForeground(1, notification)
        }

//        ContextCompat.registerReceiver(
//            this,
//            cameraReceiver,
//            IntentFilter(ACTION_SWITCH_CAMERA),
//            ContextCompat.RECEIVER_NOT_EXPORTED
//        )

        Log.e("StreamingService", "onCreate")

        // Acquire a partial WakeLock to keep CPU running
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::StreamingLock")
        wakeLock?.acquire()

        // Initialize UdpCamera2
        udpCamera2 = UdpCamera2(this, connectChecker)
        //udpCamera2.setVideoEncoderCallback { /* optional callback */ }

        // Prepare video: width=1280, height=720, fps=30, bitrate=10Mbps, rotation=0, camera=1
        udpCamera2.prepareVideo(1280, 720, 30, 4_000_000, 1, 0, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        udpCamera2.prepareAudio() // optional

        // Start streaming
        udpCamera2.startStream("udp://$serverIp:$port")

        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.e("StreamingService", "onDestroy")
        // Stop streaming
        udpCamera2.stopStream()
        // Release WakeLock
        wakeLock?.release()
        onStopped?.invoke()
    }
}