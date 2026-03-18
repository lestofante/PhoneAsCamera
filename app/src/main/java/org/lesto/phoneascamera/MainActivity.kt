package org.lesto.phoneascamera

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.CheckBox
import com.pedro.library.view.OpenGlView

class MainActivity : AppCompatActivity() {

    // visualization with ffplay -fflags nobuffer -flags low_delay -framedrop -analyzeduration 0 -probesize 32 udp://0.0.0.0:5000

    //too slow but works: ffmpeg -fflags nobuffer -flags low_delay -analyzeduration 100000 -probesize 100000 -i "udp://0.0.0.0:5000?overrun_nonfatal=1" -vf format=yuyv422 -c:v rawvideo -f v4l2 /dev/video10

    private lateinit var button: Button
    private var running = false
    private lateinit var cameraSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private val cameraIds = mutableListOf<String>()
    private var glView: OpenGlView? = null

    // In your Application class or MainActivity
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "STREAM_CHANNEL",
                "Camera Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel(this);

        requestPermission()

        cameraSpinner = findViewById(R.id.cameraSpinner)
        modeSpinner = findViewById(R.id.modeSpinner)
        loadCameras()

        Log.i("phoneascamera", "phoneascamera")
        //udpCamera2 = UdpCamera2(glView, connectChecker)

        StreamingService.onStopped = {
            runOnUiThread {
                running = false
                button.text = "START STREAM"
            }
        }

        StreamingService.onStarted = {
            runOnUiThread {
                running = true
                button.text = "STOP STREAM"
            }
        }

        glView = findViewById(R.id.openGlView)

        button = findViewById(R.id.button)
        button.setOnClickListener {
            val intent = Intent(this, StreamingService::class.java)
            Log.e("BUTTON", "CLICK $running")
            if (!running) {
                running = true
                //startStreaming()
                val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                if (res != null) {
                    button.text = "STOP STREAM"
                }else{
                    Log.e("BUTTON", "startService FAILED")
                }
            } else {
                //stopStreaming()
                stopService(intent)
                button.text = "STARTING STREAM"
                running = false
            }
        }

        val checkboxPreview: CheckBox = findViewById(R.id.checkBox_preview)
        checkboxPreview.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                glView?.visibility = View.VISIBLE
                StreamingService.instance?.setPreview(glView)
            } else {
                StreamingService.instance?.setPreview(null)
                glView?.visibility = View.INVISIBLE  // hiding it effectively clears it visually
            }
        }

        val checkboxLantern: CheckBox = findViewById(R.id.checkBox_lantern)
        checkboxLantern.setOnCheckedChangeListener { _, isChecked ->
            StreamingService.useLantern = isChecked
            StreamingService.instance?.setLantern()
        }
    }

    private fun requestPermission() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val permissionsToRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
            return
        }
    }

    private fun loadCameras() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val labels = mutableListOf<String>()
        cameraIds.clear()

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Unknown"
            }
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focal = focalLengths?.firstOrNull()?.let { " (${it}mm)" } ?: ""
            labels.add("Cam $id — $facing$focal")
            cameraIds.add(id)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cameraSpinner.adapter = adapter

        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                pos: Int,
                id: Long
            ) {
                Log.d("SPINNER", "selected cam " + cameraIds[pos])
                loadCameraModes(cameraIds[pos])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        if (cameraIds.isNotEmpty()) {
            loadCameraModes(cameraIds[0])
        }

    }

    private val cameraModes = mutableListOf<StreamingService.CameraMode>()

//    private fun loadCameraModes(cameraId: String) {
//        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
//        val chars = manager.getCameraCharacteristics(cameraId)
//        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
//
//        cameraModes.clear()
//
//        for (format in map.outputFormats) {
//            val formatName = when (format) {
//                ImageFormat.YUV_420_888 -> "YUV"
//                ImageFormat.JPEG        -> "JPEG"
//                ImageFormat.PRIVATE     -> continue // skip unknown/unusable
//                ImageFormat.RAW_SENSOR  -> "RAW"
//                else                    -> continue  // skip unknown/unusable
//            }
//            val sizes = map.getOutputSizes(format) ?: continue
//            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
//
//            // extract distinct max FPS values
//            val supportedFps = fpsRanges
//                .map { it.upper }
//                .distinct()
//                .sorted()
//
//            for (size in sizes) {
//                for (fps in supportedFps) {
//                    cameraModes.add(
//                        StreamingService.CameraMode(
//                            cameraId = cameraId,
//                            fps = fps,
//                            width = size.width,
//                            height = size.height,
//                            label = "$formatName — ${size.width}×${size.height}"
//                        )
//                    )
//                }
//            }
//        }
//
//        val adapter = ArrayAdapter(this,
//            android.R.layout.simple_spinner_item,
//            cameraModes.map { it.label })
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//        modeSpinner.adapter = adapter
//
//        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
//            override fun onItemSelected(
//                parent: AdapterView<*>,
//                view: android.view.View?,
//                pos: Int,
//                id: Long
//            ) {
//                Log.d("SPINNER", "selected mode " + cameraModes[modeSpinner.selectedItemPosition])
//                StreamingService.mode = cameraModes[modeSpinner.selectedItemPosition]
//                if (StreamingService.instance != null)
//                    StreamingService.instance?.updateCameraMode()
//            }
//
//            override fun onNothingSelected(parent: AdapterView<*>) {}
//        }
//    }

    private fun loadCameraModes(cameraId: String) {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // get supported FPS ranges
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()

        // extract distinct max FPS values
        val supportedFps = fpsRanges
            .map { it.upper }
            .distinct()
            .sorted()

        cameraModes.clear()

        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return
        for (size in sizes) {
            for (fps in supportedFps) {
                cameraModes.add(
                    StreamingService.CameraMode(
                        cameraId = cameraId,
                        width = size.width,
                        height = size.height,
                        fps = fps,
                        label = "${size.width}x${size.height} @ $fps fps"
                    )
                )
            }
        }

        val adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item,
            cameraModes.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = adapter

        StreamingService.mode = cameraModes[modeSpinner.selectedItemPosition]
        if (StreamingService.instance != null)
            StreamingService.instance?.updateCameraMode()

        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                pos: Int,
                id: Long
            ) {
                Log.d("SPINNER", "selected mode " + cameraModes[modeSpinner.selectedItemPosition])
                StreamingService.mode = cameraModes[modeSpinner.selectedItemPosition]
                if (StreamingService.instance != null)
                    StreamingService.instance?.updateCameraMode()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
}