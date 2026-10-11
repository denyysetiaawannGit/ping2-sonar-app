package com.example.pingsonar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private val actionUsbPermission = "com.example.pingsonar.USB_PERMISSION"
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private lateinit var tvDepth: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spinnerBaudRate: Spinner
    private lateinit var btnConnect: Button
    private lateinit var chartView: SimpleDepthChartView

    private val baudRates = arrayOf(115200, 9600, 19200, 38400, 57600, 230400, 460800, 921600)
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            sendPingRequest()
            handler.postDelayed(this, 150) // Polling data tiap 150 ms (~6 Hz)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (actionUsbPermission == intent?.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openSerialPort(it) }
                    } else {
                        tvStatus.text = "Status: Izin USB ditolak pengguna"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDepth = findViewById(R.id.tvDepth)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvStatus = findViewById(R.id.tvStatus)
        spinnerBaudRate = findViewById(R.id.spinnerBaudRate)
        btnConnect = findViewById(R.id.btnConnect)
        chartView = findViewById(R.id.depthChartView)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, baudRates)
        spinnerBaudRate.adapter = adapter
        spinnerBaudRate.setSelection(0) // Default 115200

        btnConnect.setOnClickListener {
            if (usbSerialPort == null) {
                initiateConnection()
            } else {
                disconnectUsb()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, IntentFilter(actionUsbPermission), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, IntentFilter(actionUsbPermission))
        }
    }

    private fun initiateConnection() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            tvStatus.text = "Status: FTDI tidak terdeteksi. Cek OTG."
            return
        }

        val driver = drivers[0]
        if (!usbManager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), flags)
            usbManager.requestPermission(driver.device, permissionIntent)
        } else {
            openSerialPort(driver.device)
        }
    }

    private fun openSerialPort(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device == device } ?: return
        val connection = usbManager.openDevice(driver.device) ?: return

        val port = driver.ports[0]
        try {
            port.open(connection)
            val selectedBaud = spinnerBaudRate.selectedItem as Int
            port.setParameters(selectedBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true

            ioManager = SerialInputOutputManager(port, this)
            Executors.newSingleThreadExecutor().submit(ioManager)

            usbSerialPort = port
            tvStatus.text = "Status: Terhubung (${driver.device.deviceName}) @ $selectedBaud bps"
            btnConnect.text = "Disconnect"
            handler.post(pollRunnable)
        } catch (e: IOException) {
            tvStatus.text = "Status: Gagal membuka port (${e.message})"
            disconnectUsb()
        }
    }

    private fun sendPingRequest() {
        try {
            usbSerialPort?.write(PingProtocol.REQUEST_DISTANCE_SIMPLE, 100)
        } catch (_: IOException) {}
    }

    override fun onNewData(data: ByteArray?) {
        data?.let { bytes ->
            val result = PingProtocol.parseDistanceSimple(bytes, bytes.size)
            result?.let { (depthM, confidence) ->
                runOnUiThread {
                    tvDepth.text = String.format("%.2f m", depthM)
                    tvConfidence.text = "Confidence: $confidence %"
                    chartView.addDepth(depthM)
                }
            }
        }
    }

    override fun onRunError(e: Exception?) {
        runOnUiThread {
            tvStatus.text = "Status: Koneksi terputus"
            disconnectUsb()
        }
    }

    private fun disconnectUsb() {
        handler.removeCallbacks(pollRunnable)
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            usbSerialPort?.close()
        } catch (_: IOException) {}
        usbSerialPort = null

        tvStatus.text = "Status: Disconnected"
        btnConnect.text = "Connect"
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectUsb()
        unregisterReceiver(usbReceiver)
    }
}
