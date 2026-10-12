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
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
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

    private val rxBuffer = mutableListOf<Byte>()
    private var totalBytesReceived = 0L
    private val baudRates = arrayOf(115200, 9600, 19200, 38400, 57600, 230400, 460800, 921600)
    private val handler = Handler(Looper.getMainLooper())

    // Polling berkala jika auto-stream belum aktif
    private val pollRunnable = object : Runnable {
        override fun run() {
            sendPingRequest()
            handler.postDelayed(this, 200)
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
                        tvStatus.text = "Status: Izin USB ditolak oleh sistem"
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
            try {
                if (usbSerialPort == null) {
                    initiateConnection()
                } else {
                    disconnectUsb()
                }
            } catch (e: Exception) {
                tvStatus.text = "Error: ${e.message}"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable().apply {
            addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
            addProduct(0x0403, 0x6010, FtdiSerialDriver::class.java)
            addProduct(0x0403, 0x6011, FtdiSerialDriver::class.java)
            addProduct(0x0403, 0x6014, FtdiSerialDriver::class.java)
            addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java)
            addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
            addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
            addProduct(0x1A86, 0x5523, Ch34xSerialDriver::class.java)
            addProduct(0x067B, 0x2303, ProlificSerialDriver::class.java)
        }
        return UsbSerialProber(customTable)
    }

    private fun initiateConnection() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        var drivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            drivers = getCustomProber().findAllDrivers(usbManager)
        }

        if (drivers.isEmpty()) {
            tvStatus.text = "Status: Perangkat USB serial tidak ditemukan"
            Toast.makeText(this, "Perangkat USB tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = drivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = Intent(actionUsbPermission).apply {
                `package` = packageName
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pIntent = PendingIntent.getBroadcast(this, 0, permissionIntent, flags)
            usbManager.requestPermission(device, pIntent)
        } else {
            openSerialPort(device)
        }
    }

    private fun openSerialPort(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        var drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            drivers = getCustomProber().findAllDrivers(usbManager)
        }

        val driver = drivers.firstOrNull { it.device == device } ?: return
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

            synchronized(rxBuffer) {
                rxBuffer.clear()
            }
            totalBytesReceived = 0L

            ioManager = SerialInputOutputManager(port, this)
            Executors.newSingleThreadExecutor().submit(ioManager)

            usbSerialPort = port
            tvStatus.text = "Status: Terhubung @ $selectedBaud bps (Menunggu data...)"
            btnConnect.text = "Disconnect"

            // Kirim perintah aktifkan continuous stream pesan 1212 setiap 100ms
            enableContinuousStream()

            // Jalankan polling cadangan
            handler.post(pollRunnable)
        } catch (e: Exception) {
            tvStatus.text = "Status: Gagal inisialisasi port (${e.message})"
            disconnectUsb()
        }
    }

    // Ping Protocol Msg ID 1003 (control_continuous_start): Minta ID 1212 dikirim otomatis
    private fun enableContinuousStream() {
        val startStreamPacket = byteArrayOf(
            0x42, 0x52,             // 'B', 'R'
            0x02, 0x00,             // Payload len: 2 bytes
            0xEB.toByte(), 0x03,   // Msg ID: 1003 (0x03EB little-endian)
            0x00, 0x00,             // Src, Dst
            0xBC.toByte(), 0x04,   // Target Msg ID: 1212
            0x10, 0x01             // Checksum
        )
        try {
            usbSerialPort?.write(startStreamPacket, 100)
        } catch (_: IOException) {}
    }

    private fun sendPingRequest() {
        try {
            usbSerialPort?.write(PingProtocol.REQUEST_DISTANCE_SIMPLE, 100)
        } catch (_: IOException) {}
    }

    override fun onNewData(data: ByteArray?) {
        data?.let { bytes ->
            synchronized(rxBuffer) {
                totalBytesReceived += bytes.size
                for (b in bytes) {
                    rxBuffer.add(b)
                }

                val result = PingProtocol.parseBuffer(rxBuffer)
                if (result != null) {
                    val depthM = result.first
                    val confidence = result.second
                    runOnUiThread {
                        tvDepth.text = String.format("%.2f m", depthM)
                        tvConfidence.text = "Confidence: $confidence %"
                        tvStatus.text = "Status: Menerima data (${totalBytesReceived} bytes)"
                        chartView?.addDepth(depthM)
                    }
                } else {
                    // Update byte counter agar terlihat jika sinyal fisik masuk
                    runOnUiThread {
                        tvStatus.text = "Status: Rx ${totalBytesReceived} bytes (buffer:${rxBuffer.size})"
                    }
                }

                if (rxBuffer.size > 2048) {
                    rxBuffer.clear()
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
        } catch (_: IOException) {}
        usbSerialPort = null

        synchronized(rxBuffer) {
            rxBuffer.clear()
        }

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
