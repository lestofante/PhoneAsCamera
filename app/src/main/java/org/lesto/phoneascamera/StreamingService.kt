package org.lesto.phoneascamera

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.udp.UdpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.atomic.AtomicBoolean

class StreamingService : Service() {
    data class CameraMode(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val label: String,
    )
    companion object {
        var instance: StreamingService? = null
        const val ACTION_SWITCH_CAMERA = "org.lesto.phoneascamera.SWITCH_CAMERA"
        const val EXTRA_CAMERA_ID = "camera_id"
        var onStopped: (() -> Unit)? = null
        var onStarted: (() -> Unit)? = null
        val serverIp = "192.168.178.25"  // replace with receiver IP
        val port = 5000
        @Volatile var mode :CameraMode = CameraMode("0", 1280, 720, 30, "DEFAULT")
        var useLantern = false
    }

    private lateinit var udpCamera2: UdpCamera2
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnecting = AtomicBoolean(false)
    private var retry = 0
    @Volatile private var reloading = false



//    fun switchCamera(new_mode :CameraMode) {
//        mode = new_mode
//    }

    fun startStreaming(){
        if (retry > 1200)//if failing many times, ~10 min
        {
            stopSelf()
        }
        udpCamera2.stopStream()
        Thread.sleep(300)
        udpCamera2.switchCamera(mode.cameraId)
        udpCamera2.prepareVideo(mode.width, mode.height, mode.fps, 40_000_000, 1, 0, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        udpCamera2.prepareAudio() // optional
        // Start streaming
        udpCamera2.startStream("udp://$serverIp:$port")
        if (!udpCamera2.enableOpticalVideoStabilization()) {
            udpCamera2.enableVideoStabilization()
        }

        setLantern()
        reconnecting.set(false)
    }

    fun setPreview(view: OpenGlView?) {
        //TODO: BROKEN
        if (udpCamera2.isStreaming) {
            if (view != null) {
                udpCamera2.replaceView(view)
            } else {
                udpCamera2.replaceView(this) // back to internal windowmanager view
            }
        }
    }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() {
            retry = 0 // reset retry counter
            Log.d("UDP", "Connected")
            onStarted?.invoke()
        }
        override fun onConnectionFailed(reason: String) {
            retry++
            Log.e("UDP", "Failed: $reason n. $retry")
            if (!reconnecting.getAndSet(true)) {
                Thread {
                    Thread.sleep(500)
                    startStreaming()
                }.start()
            }
        }
        override fun onNewBitrate(bitrate: Long) {
            Log.d("UDP", "someone requested bitrate: $bitrate")
        }

        override fun onDisconnect() {
            if (instance == null){
                Log.d("UDP", "Disconnected for closing")
                // if instance is NULL, we are closing up
                return
            }
            if (reloading) {
                Log.d("UDP", "Disconnected for reloading")
                reloading = false
                if (!reconnecting.getAndSet(true)) {
                    Thread {
                        startStreaming()
                    }.start()
                }
            }else{
                retry++
                Log.d("UDP", "Disconnected")
                if (!reconnecting.getAndSet(true)) {
                    Thread {
                        Thread.sleep(500)
                        startStreaming()
                    }.start()
                }
            }
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
        startStreaming()

        instance = this
    }

    override fun onDestroy() {
        instance = null
        reloading = false
        super.onDestroy()
        Log.e("StreamingService", "onDestroy")
        // Stop streaming
        udpCamera2.stopStream()
        // Release WakeLock
        wakeLock?.release()
        onStopped?.invoke()
    }

    fun updateCameraMode() {
        reloading = true
        udpCamera2.stopStream()
    }

    fun setLantern() {
        if (useLantern){
            udpCamera2.enableLantern()
        }else{
            udpCamera2.disableLantern()
        }
    }
}