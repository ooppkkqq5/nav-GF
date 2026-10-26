package com.motonav.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/**
 * BLE 客户端：连接圆屏、握手、收发协议帧。
 *
 * GATT 契约（与固件一致）：
 *   Service  7e57a000-b50c-4b6a-9c57-40a54e8e1000
 *   RX(W)    7e57a001-...  encrypted write，手机 → 设备
 *   TX(N)    7e57a002-...  encrypted notify，设备 → 手机
 *   CCCD     00002902-0000-1000-8000-00805f9b34fb
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBleLog(msg: String)
        fun onScanResult(device: BluetoothDevice, name: String?, rssi: Int)
        fun onScanStopped()
        fun onConnected(deviceName: String?)
        fun onHandshakeReady(sessionId: Int, maxFrameSize: Int)
        fun onDisconnected()
        fun onDeviceCommand(cmd: Proto.DeviceCommand)
        fun onAck(ackSeq: Int, status: Int)
    }

    companion object {
        private const val TAG = "BleClient"

        val SERVICE: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
        val RX: UUID = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
        val TX: UUID = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val HEARTBEAT_MS = 1000
        private const val DEFAULT_MTU = 23

        /** 我们支持的能力：导航 + 触摸命令 */
        private const val OUR_CAPS =
            Proto.CAP_NAVIGATION or Proto.CAP_TOUCH_COMMANDS or Proto.CAP_COMMAND_ACK
    }

    private val handler = Handler(Looper.getMainLooper())
    private val gattExecutor = Executors.newSingleThreadExecutor()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private var adapter: BluetoothAdapter? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    private var sessionId = 0
    private var txSequence = 1
    private var maxFrameSize = 20
    private var mtuValue = DEFAULT_MTU

    @Volatile private var handshakeStep = 0   // 0=未开始 1=已发Starting 2=已发Ready
    @Volatile private var ready = false
    @Volatile private var scanning = false

    private var lastRxMs = 0L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (ready) {
                val hb = Proto.heartbeat(sessionId, (System.currentTimeMillis() and 0xFFFFFFFF).toInt())
                sendMessage(Proto.MSG_HEARTBEAT, hb)
                if (System.currentTimeMillis() - lastRxMs > 3000) {
                    listener.onBleLog("⚠ 3 秒未收到设备帧，链路可能失活")
                }
            }
            handler.postDelayed(this, HEARTBEAT_MS.toLong())
        }
    }

    // ---------------- 扫描 ----------------

    fun startScan() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = mgr?.adapter
        if (adapter == null || !adapter!!.isEnabled) {
            listener.onBleLog("蓝牙未开启")
            return
        }
        if (!hasPerm(android.Manifest.permission.BLUETOOTH_SCAN) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listener.onBleLog("缺少 BLUETOOTH_SCAN 权限")
            return
        }
        scanner = adapter!!.bluetoothLeScanner
        if (scanner == null) {
            listener.onBleLog("扫描器不可用")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner!!.startScan(listOf(filter), settings, scanCallback)
        listener.onBleLog("开始扫描 MOTO GPS…")
    }

    fun stopScan() {
        if (scanning) {
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
            scanning = false
        }
        listener.onScanStopped()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            listener.onScanResult(result.device, name, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onBleLog("扫描失败: $errorCode")
            scanning = false
            listener.onScanStopped()
        }
    }

    // ---------------- 连接 ----------------

    fun connect(device: BluetoothDevice) {
        stopScan()
        listener.onBleLog("连接 ${device.address} …")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        ready = false
        handshakeStep = 0
        handler.removeCallbacks(heartbeatRunnable)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        txChar = null
        writeQueue.clear()
        writing = false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onBleLog("已连接，请求 MTU 517")
                listener.onConnected(g.device.name)
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onBleLog("连接断开 (status=$status)")
                ready = false
                handshakeStep = 0
                handler.removeCallbacks(heartbeatRunnable)
                listener.onDisconnected()
                try { g.close() } catch (_: Exception) {}
                gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuValue = mtu
            maxFrameSize = (mtu - 3).coerceAtMost(512).coerceAtLeast(13)
            listener.onBleLog("MTU=$mtu, maxFrameSize=$maxFrameSize")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onBleLog("服务发现失败: $status")
                return
            }
            val svc = g.getService(SERVICE)
            if (svc == null) {
                listener.onBleLog("未找到导航服务，UUID 不匹配")
                return
            }
            rxChar = svc.getCharacteristic(RX)
            txChar = svc.getCharacteristic(TX)
            if (rxChar == null || txChar == null) {
                listener.onBleLog("缺少 RX/TX 特征")
                return
            }
            listener.onBleLog("找到服务，订阅 TX 通知")
            g.setCharacteristicNotification(txChar, true)
            val desc = txChar!!.getDescriptor(CCCD)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            } else {
                listener.onBleLog("警告：TX 缺少 CCCD 描述符")
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onBleLog("TX 通知已开启，开始握手")
                beginHandshake()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                // 5 = INSUFFICIENT_AUTHENTICATION, 8 = INSUFFICIENT_ENCRYPTION
                if (status == 5 || status == 8) {
                    listener.onBleLog("需要加密连接，发起配对…")
                    try { g.device.createBond() } catch (e: Exception) {
                        listener.onBleLog("配对请求失败: ${e.message}")
                    }
                } else {
                    listener.onBleLog("CCCD 写入失败: $status")
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onBleLog("写入失败 status=$status")
                if (status == 5 || status == 8) {
                    listener.onBleLog("需要加密连接，发起配对…")
                    try { g.device.createBond() } catch (_: Exception) {}
                }
            }
            onWriteCompleted()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncoming(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            handleIncoming(v)
        }
    }

    // ---------------- 握手 ----------------

    private fun beginHandshake() {
        sessionId = (1..Int.MAX_VALUE).random()
        txSequence = 1
        handshakeStep = 1
        lastRxMs = System.currentTimeMillis()
        val cs = Proto.connectionStatus(
            role = Proto.ROLE_PHONE,
            state = Proto.STATE_STARTING,
            capabilities = OUR_CAPS,
            sessionId = sessionId,
            maxFrameSize = maxFrameSize,
            heartbeatMs = HEARTBEAT_MS,
        )
        sendMessage(Proto.MSG_CONNECTION_STATUS, cs)
        listener.onBleLog("→ ConnectionStatus(Phone, Starting) session=$sessionId")
    }

    private fun handleIncoming(raw: ByteArray) {
        val frame = Proto.decodeFrame(raw)
        if (frame == null) {
            listener.onBleLog("收到非法帧（魔数/CRC 错），丢弃 ${raw.size}B")
            return
        }
        lastRxMs = System.currentTimeMillis()

        when (frame.type) {
            Proto.MSG_CONNECTION_STATUS -> {
                val p = frame.payload
                if (p.size < 17) return
                val role = p[1].toInt() and 0xFF
                val state = p[2].toInt() and 0xFF
                val devSession = Proto.getU32(p, 9)
                val devMax = Proto.getU16(p, 13)

                if (role != Proto.ROLE_DEVICE) return
                if (devSession != sessionId) {
                    listener.onBleLog("⚠ session 不一致，重新握手")
                    beginHandshake()
                    return
                }
                when (state) {
                    Proto.STATE_READY -> {
                        if (handshakeStep == 1) {
                            handshakeStep = 2
                            maxFrameSize = minOf(maxFrameSize, devMax).coerceAtLeast(13)
                            val cs = Proto.connectionStatus(
                                role = Proto.ROLE_PHONE,
                                state = Proto.STATE_READY,
                                capabilities = OUR_CAPS,
                                sessionId = sessionId,
                                maxFrameSize = maxFrameSize,
                                heartbeatMs = HEARTBEAT_MS,
                            )
                            sendMessage(Proto.MSG_CONNECTION_STATUS, cs)
                            listener.onBleLog("← Device Ready；→ Phone Ready")
                        } else if (handshakeStep == 2) {
                            ready = true
                            listener.onBleLog("✓ 握手完成，maxFrameSize=$maxFrameSize")
                            listener.onHandshakeReady(sessionId, maxFrameSize)
                            handler.removeCallbacks(heartbeatRunnable)
                            handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS.toLong())
                        }
                    }
                    Proto.STATE_CLOSING -> {
                        listener.onBleLog("设备要求关闭连接")
                        disconnect()
                    }
                    else -> listener.onBleLog("设备状态=$state")
                }
            }

            Proto.MSG_ACK -> {
                val p = frame.payload
                if (p.size >= 6) {
                    listener.onAck(Proto.getU16(p, 1), p[3].toInt() and 0xFF)
                }
            }

            Proto.MSG_DEVICE_COMMAND -> {
                val cmd = Proto.decodeDeviceCommand(frame.payload)
                if (cmd != null) listener.onDeviceCommand(cmd)
            }

            Proto.MSG_HEARTBEAT -> {
                // 仅刷新 lastRxMs
            }

            else -> {
                listener.onBleLog("收到未知消息类型 0x${frame.type.toString(16)}")
            }
        }
    }

    // ---------------- 发送 ----------------

    /** 发送一条逻辑消息（自动分片 + 序号） */
    fun sendMessage(type: Int, payload: ByteArray, ackRequested: Boolean = false) {
        val seq = txSequence
        txSequence = if (txSequence >= 65535) 1 else txSequence + 1
        val frames = Proto.fragment(type, seq, payload, maxFrameSize, ackRequested, urgent = false)
        synchronized(writeQueue) {
            writeQueue.addAll(frames)
        }
        pumpQueue()
    }

    private fun pumpQueue() {
        synchronized(writeQueue) {
            if (writing) return
            val next = writeQueue.removeFirstOrNull() ?: return
            writing = true
            writeRaw(next)
        }
    }

    private fun writeRaw(data: ByteArray) {
        val g = gatt
        val ch = rxChar
        if (g == null || ch == null) {
            writing = false
            return
        }
        try {
            gattExecutor.submit {
                try {
                    val done = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        ch.value = data
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(ch)
                    }
                    if (!done) {
                        handler.post {
                            writing = false
                            pumpQueue()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "write failed", e)
                    handler.post {
                        writing = false
                        pumpQueue()
                    }
                }
            }.get()
        } catch (e: Exception) {
            Log.w(TAG, "submit failed", e)
            writing = false
        }
    }

    /** 由 GATT 回调驱动：一次写完成后继续下一条 */
    fun onWriteCompleted() {
        handler.post {
            synchronized(writeQueue) {
                writing = false
            }
            pumpQueue()
        }
    }

    val isReady: Boolean get() = ready

    private fun hasPerm(p: String): Boolean =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
