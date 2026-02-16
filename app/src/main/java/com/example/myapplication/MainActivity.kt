package com.example.myapplication

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.myapplication.USB_PERMISSION"
        private const val TAG = "OpenMVCam"
        
        private const val VID_OPENMV = 0x1209
        private const val PID_OPENMV = 0xabd1
        private const val VID_STM32 = 0x0483
        private const val PID_STM32 = 0x5740
        private const val VID_NEW = 0x37C5
        private const val PID_NEW = 0x924A
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: android.hardware.usb.UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            statusText.value = "Permission granted. Connecting..."
                            connectUsb(this)
                        }
                    } else {
                        statusText.value = "Permission DENIED."
                        Log.d(TAG, "permission denied for device $device")
                    }
                }
            }
        }
    }
    
    private val openMvBitmap = mutableStateOf<Bitmap?>(null)
    private val statusText = mutableStateOf("Initializing...")
    
    private var usbPort: UsbSerialPort? = null
    private var readingThread: Thread? = null
    @Volatile private var isRunning = false
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraExecutor = Executors.newSingleThreadExecutor()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 1. Device Camera Feed
                        DeviceCameraPreview(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.4f)
                        )
                        
                        // 2. OpenMV Camera Feed
                        OpenMVCamPreview(
                            bitmap = openMvBitmap.value,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.4f)
                        )
                        
                        // Status Overlay (Click to Retry)
                        if (openMvBitmap.value == null) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clickable { 
                                        stopReading()
                                        checkUsbDevices() 
                                    }
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = statusText.value, 
                                    color = Color.White, 
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Text(
                                    text = "(Tap to retry USB check)", 
                                    color = Color.LightGray, 
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        window.decorView.postDelayed({ checkUsbDevices() }, 1000)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReading()
        unregisterReceiver(usbPermissionReceiver)
        cameraExecutor.shutdown()
    }

    private fun stopReading() {
        isRunning = false
        try {
            readingThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        try {
            usbPort?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        usbPort = null
    }

    private fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable()
        customTable.addProduct(VID_OPENMV, PID_OPENMV, CdcAcmSerialDriver::class.java)
        customTable.addProduct(VID_STM32, PID_STM32, CdcAcmSerialDriver::class.java)
        customTable.addProduct(VID_NEW, PID_NEW, CdcAcmSerialDriver::class.java)
        return UsbSerialProber(customTable)
    }

    private fun checkUsbDevices() {
        try {
            statusText.value = "Checking USB Devices..."
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            
            val deviceList = manager.deviceList
            if (deviceList.isEmpty()) {
                statusText.value = "No USB Devices Attached.\nCheck OTG Cable."
                return
            }

            var availableDrivers = getCustomProber().findAllDrivers(manager)
            
            if (availableDrivers.isEmpty()) {
                availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            }
            
            if (availableDrivers.isEmpty()) {
                val sb = StringBuilder("No Driver Found.\nAttached:")
                deviceList.values.forEach { 
                    sb.append("\nVID:${String.format("%04X", it.vendorId)} PID:${String.format("%04X", it.productId)}") 
                }
                statusText.value = sb.toString()
                return
            }
            
            val driver = availableDrivers[0]
            val device = driver.device
            
            statusText.value = "Found: ${device.deviceName}\nRequesting Permission..."

            if (!manager.hasPermission(device)) {
                val intent = Intent(ACTION_USB_PERMISSION)
                intent.setPackage(packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                manager.requestPermission(device, permissionIntent)
            } else {
                connectUsb(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CheckDevices error", e)
            statusText.value = "Scan Error: ${e.message}"
        }
    }

    private fun connectUsb(device: android.hardware.usb.UsbDevice) {
        try {
            statusText.value = "Connecting to device..."
            Log.d(TAG, "connectUsb: Attempting to connect to device ${device.deviceName}")

            if (usbPort != null) {
                Log.d(TAG, "connectUsb: Already connected, skipping.")
                return
            }

            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            
            var driver = getCustomProber().probeDevice(device)
            if (driver == null) {
                driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            }
            
            if (driver == null) {
                 statusText.value = "Error: Driver creation failed"
                 Log.e(TAG, "connectUsb: Failed to find driver for device.")
                 return
            }

            val connection = manager.openDevice(driver.device)
            if (connection == null) {
                 statusText.value = "Error: Open Failed (null connection)"
                 Log.e(TAG, "connectUsb: Failed to open device connection.")
                 return
            }

            usbPort = driver.ports[0]
            Log.d(TAG, "connectUsb: Opening port.")
            usbPort?.open(connection) // This line could throw an exception if device is busy/error
            Log.d(TAG, "connectUsb: Port opened. Setting parameters.")
            usbPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Log.d(TAG, "connectUsb: Parameters set. Toggling DTR/RTS.")
            
            // Toggle DTR/RTS to try and reset the serial port state on the OpenMV side
            usbPort?.dtr = false
            usbPort?.rts = false
            Thread.sleep(100) // Short delay
            usbPort?.dtr = true
            usbPort?.rts = true
            Log.d(TAG, "connectUsb: DTR/RTS toggled.")
            
            try {
                usbPort?.purgeHwBuffers(true, true)
                Log.d(TAG, "connectUsb: Hardware buffers purged.")
            } catch (e: Exception) {
                Log.w(TAG, "connectUsb: Failed to purge hardware buffers: ${e.message}")
                // Some drivers don't support purge, not a critical error if connection works otherwise
            }
            
            statusText.value = "Connected! Waiting for stream..."
            Log.d(TAG, "connectUsb: Calling startReading().")
            startReading()
            Log.d(TAG, "connectUsb: startReading() called, connectUsb exiting.")

        } catch (e: Exception) {
            Log.e(TAG, "Error opening or configuring port: ", e)
            statusText.value = "Error: ${e.message}"
            stopReading()
        }
    }

    private fun startReading() {
        if (isRunning) {
            Log.d(TAG, "startReading: Read thread already running.")
            return
        }
        isRunning = true
        readingThread = Thread {
            Log.d(TAG, "startReading: Read thread started.")
            val readBuf = ByteArray(2048) // Buffer for reading chunks from USB
            val streamBuffer = ByteArray(512 * 1024) // 512KB circular buffer for stream assembly
            var streamPos = 0 // Current write position in streamBuffer
            val debugBytesBuffer = StringBuilder() // For Logcat hex/ASCII debugging

            val streamStartTime = System.currentTimeMillis()
            var switchedToDiagnostic = false

            Log.d(TAG, "startReading: Entering MJPEG Scanner loop.")
            
            while (isRunning && usbPort != null) {
                try {
                    val len = usbPort!!.read(readBuf, 100) // Read from USB with a timeout
                    if (len > 0) {
                        // --- Diagnostic Logging --- 
                        for (i in 0 until len) {
                            debugBytesBuffer.append("%02X ".format(readBuf[i]))
                        }
                        if (debugBytesBuffer.length > 200 || len > 0 && debugBytesBuffer.isEmpty()) {
                            val hexString = debugBytesBuffer.toString().take(200)
                            val asciiString = readBuf.take(if(len>20) 20 else len).map { if (it.toInt() in 32..126) it.toInt().toChar() else '.' }.joinToString("")
                            Log.d(TAG, "RX: $hexString... (ASCII: $asciiString)")
                            debugBytesBuffer.clear()
                        }
                        // --- End Diagnostic Logging ---

                        // If we've been waiting too long, switch to diagnostic dump
                        if (!switchedToDiagnostic && System.currentTimeMillis() - streamStartTime > 5000 && openMvBitmap.value == null) {
                            runOnUiThread { statusText.value = "No stream detected. Dumping raw data to Logcat (tap to restart scan)..." }
                            switchedToDiagnostic = true
                        }

                        if (switchedToDiagnostic) {
                            streamPos = 0 // Effectively discard as we're just monitoring raw bytes
                            continue
                        }

                        // Write incoming bytes into the circular streamBuffer
                        if (streamPos + len > streamBuffer.size) {
                            val bytesToEnd = streamBuffer.size - streamPos
                            System.arraycopy(readBuf, 0, streamBuffer, streamPos, bytesToEnd)
                            System.arraycopy(readBuf, bytesToEnd, streamBuffer, 0, len - bytesToEnd)
                            streamPos = len - bytesToEnd
                        } else {
                            System.arraycopy(readBuf, 0, streamBuffer, streamPos, len)
                            streamPos += len
                        }
                        
                        // If buffer gets too full without finding a frame, aggressively clear it
                        if (streamPos > streamBuffer.size / 2) {
                            Log.w(TAG, "Stream buffer getting full without frame, clearing to resync.")
                            streamPos = 0 // Clear buffer to prevent stale data blocking new frames
                        }

                        // Scan for MJPEG Frame (SOI: FF D8, EOI: FF D9) within the streamBuffer
                        while (streamPos >= 2) { 
                            var soiIndex = -1 
                            for (i in 0 until streamPos - 1) {
                                if (streamBuffer[i] == 0xFF.toByte() && streamBuffer[i+1] == 0xD8.toByte()) {
                                    soiIndex = i
                                    break
                                }
                            }
                            
                            if (soiIndex == -1) {
                                if (streamPos > 0) {
                                    streamBuffer[0] = streamBuffer[streamPos - 1]
                                    streamPos = 1
                                } else {
                                    streamPos = 0
                                }
                                break 
                            }
                            
                            var eoiIndex = -1
                            for (i in soiIndex + 2 until streamPos - 1) {
                                if (streamBuffer[i] == 0xFF.toByte() && streamBuffer[i+1] == 0xD9.toByte()) {
                                    eoiIndex = i + 1
                                    break
                                }
                            }
                            
                            if (eoiIndex != -1) {
                                val frameLength = eoiIndex - soiIndex + 1
                                if (frameLength > 0) {
                                    val bitmap = BitmapFactory.decodeByteArray(streamBuffer, soiIndex, frameLength)
                                    if (bitmap != null) {
                                        openMvBitmap.value = bitmap 
                                        runOnUiThread { if (statusText.value != "Streaming...") statusText.value = "Streaming..." }
                                        switchedToDiagnostic = false // Reset diagnostic mode if frame found
                                    } else {
                                        Log.w(TAG, "Failed to decode bitmap from frame ($soiIndex to $eoiIndex, length $frameLength)")
                                    }
                                } else {
                                    Log.w(TAG, "Invalid frame length ($frameLength) for frame $soiIndex to $eoiIndex")
                                }
                                
                                val bytesConsumed = eoiIndex + 1
                                val remainingBytes = streamPos - bytesConsumed
                                if (remainingBytes > 0) {
                                    System.arraycopy(streamBuffer, bytesConsumed, streamBuffer, 0, remainingBytes)
                                    streamPos = remainingBytes
                                } else {
                                    streamPos = 0
                                }
                            } else {
                                if (soiIndex > 0) {
                                    val bytesToShift = streamPos - soiIndex
                                    System.arraycopy(streamBuffer, soiIndex, streamBuffer, 0, bytesToShift)
                                    streamPos = bytesToShift
                                }
                                break 
                            }
                        }
                    } else { // len is 0, no data read
                        try { Thread.sleep(5) } catch (ignored: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read error in stream processing: ", e)
                    runOnUiThread { statusText.value = "Stream Err: ${e.message}" }
                    try { Thread.sleep(500) } catch (ignored: Exception) {}
                    streamPos = 0 // Reset buffer on severe error
                    switchedToDiagnostic = false // Also reset diagnostic mode
                }
            }
            Log.d(TAG, "startReading: Read thread finished.")
        }.apply { start() }
    }
}

@Composable
fun DeviceCameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = modifier,
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                } catch (e: Exception) {
                    Log.e("CameraX", "Binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
fun OpenMVCamPreview(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "OpenMV Feed",
            modifier = modifier,
            contentScale = ContentScale.FillBounds
        )
    } else {
        Box(modifier = modifier)
    }
}
