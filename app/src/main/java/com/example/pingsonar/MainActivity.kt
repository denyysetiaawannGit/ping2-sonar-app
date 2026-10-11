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
import android.widget.Toast
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
    private var chartView: SimpleDepthChartView? = null

    private val baudRates = arrayOf(115200, 9600, 19200, 38400, 57600, 230400, 460800, 921600)
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            sendPingRequest()
            handler.postDelayed(this, 150)
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
                        tvStatus.text = "Status: Izin USB ditolak"
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
        spinnerBaudRate.setSelection(0)

        btnConnect.setOnClickListener {
            try {
                if (usbSerialPort == null) {
                    initiateConnection()
                } else {
                    disconnectUsb()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                tvStatus.text = "Error: ${e.message}"
            }
        }

        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun initiateConnection() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            tvStatus.text = "Status: Modul FTDI tidak terdeteksi (periksa OTG)"
            Toast.makeText(this, "Perangkat FTDI tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openSerialPort(device)
        }
    }

    private fun openSerialPort(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = availableDrivers.firstOrNull { it.device == device } ?: return
        
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            tvStatus.text = "Status: Gagal membuka koneksi USB"
            return
        }

        try {
            val port = driver.ports[0]
            port.open(connection)
            
            val selectedBaud = spinnerBaudRate.selectedItem as Int
            port.setParameters(selectedBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            try {
                port.dtr = true
                port.rts = true
            } catch (_: Exception) {}

            ioManager = SerialInputOutputManager(port, this)
            Executors.newSingleThreadExecutor().submit(ioManager)

            usbSerialPort = port
            tvStatus.text = "Status: Terhubung @ $selectedBaud bps"
            btnConnect.text = "Disconnect"
            handler.post(pollRunnable)
        } catch (e: Exception) {
            tvStatus.text = "Status: Gagal inisialisasi port (${e.message})"
            Toast.makeText(this, "Gagal konek: ${e.message}", Toast.LENGTH_SHORT).show()
            disconnectUsb()
        }
    }

    private fun sendPingRequest() {
        try {
            usbSerialPort?.write(PingProtocol.REQUEST_DISTANCE_SIMPLE, 100)
        } catch (_: Exception) {}
    }

    override fun onNewData(data: ByteArray?) {
        data?.let { bytes ->
            val result = PingProtocol.parseDistanceSimple(bytes, bytes.size)
            result?.let { (depthM, confidence) ->
                runOnUiThread {
                    tvDepth.text = String.format("%.2f m", depthM)
                    tvConfidence.text = "Confidence: $confidence %"
                    chartView?.addDepth(depthM)
                }
            }
        }
    }

    override fun onRunError(e: Exception?) {
        runOnUiThread {
            tvStatus.text = "Status: Komunikasi terputus"
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
        } catch (_: Exception) {}
        usbSerialPort = null

        tvStatus.text = "Status: Disconnected"
        btnConnect.text = "Connect"
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectUsb()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}
