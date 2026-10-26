package com.motonav.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.roundToInt

/**
 * 导航核心：串起「定位 → 路线 → 计算 → BLE 发送」。
 *
 * 用单例持有，生命周期比 Activity 长，配合 NavService 前台服务保证息屏不断。
 */
@SuppressLint("MissingPermission")
object NavCore {

    private const val TAG = "NavCore"
    private const val TICK_MS = 1000L

    interface Listener {
        fun onLog(msg: String)
        fun onStatus(status: String)
        fun onNavState(s: NavState)
        fun onDevices(found: List<Triple<BluetoothDevice, String?, Int>>)
        fun onCandidates(list: List<Pair<String, LatLon>>)
        fun onBleReady(sessionId: Int, maxFrameSize: Int)
    }

    var listener: Listener? = null

    val ble: BleClient by lazy { BleClient(appContext, bleListener) }
    val engine = NavEngine()

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    private var lm: LocationManager? = null
    private var lastLoc: Location? = null
    private var route: Route? = null
    private var destination: LatLon? = null
    private var navigating = false
    private var devices = mutableListOf<Triple<BluetoothDevice, String?, Int>>()

    private var lastSentKey = ""

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    // ---------------- 定位 ----------------

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLoc = location
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("旧接口")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun startLocation() {
        val m = lm ?: return
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            }
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            }
            val last = m.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) lastLoc = last
            listener?.onLog("定位已启动")
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission", e)
            listener?.onLog("缺少定位权限")
        }
    }

    fun stopLocation() {
        try { lm?.removeUpdates(locListener) } catch (_: Exception) {}
    }

    val position: LatLon?
        get() = lastLoc?.let { LatLon(it.latitude, it.longitude) }

    val speedKph: Double
        get() = lastLoc?.let { if (it.hasSpeed()) it.speed * 3.6 else 0.0 } ?: 0.0

    val headingDeg: Double
        get() = lastLoc?.let { if (it.hasBearing()) it.bearing.toDouble() else 0.0 } ?: 0.0

    // ---------------- 扫描 ----------------

    fun startScan() {
        devices.clear()
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()

    fun connect(device: BluetoothDevice) = ble.connect(device)

    fun disconnect() {
        stopNav()
        ble.disconnect()
    }

    // ---------------- 路线 ----------------

    fun searchDestination(text: String) {
        listener?.onLog("搜索：$text")
        Thread {
            val list = RouteService.geocodeList(text, 5)
            handler.post {
                if (list.isEmpty()) {
                    listener?.onLog("没找到地点，换个关键词试试")
                    listener?.onCandidates(emptyList())
                } else {
                    listener?.onCandidates(list)
                }
            }
        }.start()
    }

    fun selectDestination(p: LatLon, label: String) {
        destination = p
        val from = position
        if (from == null) {
            listener?.onStatus("还没有定位，等 GPS 拿到坐标")
            return
        }
        listener?.onStatus("规划路线中…")
        Thread {
            val r = RouteService.route(from, p)
            handler.post {
                if (r == null) {
                    listener?.onStatus("路线规划失败（检查网络）")
                    return@post
                }
                route = r
                engine.setRoute(r)
                listener?.onLog(
                    "路线：${"%.0f".format(r.distanceM / 1000)} km，" +
                    "${(r.durationS / 60).roundToInt()} 分钟，${r.steps.size} 个步骤"
                )
                listener?.onStatus("去 $label")
            }
        }.start()
    }

    // ---------------- 导航循环 ----------------

    fun startNav() {
        if (route == null) {
            listener?.onStatus("先选目的地")
            return
        }
        if (!ble.isReady) {
            listener?.onStatus("先连接圆屏")
            return
        }
        navigating = true
        lastSentKey = ""
        handler.removeCallbacks(tick)
        handler.post(tick)
        listener?.onLog("导航已开始")
    }

    fun stopNav() {
        navigating = false
        handler.removeCallbacks(tick)
        engine.clear()
        route = null
    }

    val isNavigating: Boolean get() = navigating

    private val tick = object : Runnable {
        override fun run() {
            if (!navigating) return
            try {
                val pos = position
                if (pos == null) {
                    listener?.onStatus("等待 GPS 定位…")
                } else {
                    val st = engine.update(pos, speedKph)
                    listener?.onNavState(st)
                    sendSnapshot(st, pos)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun sendSnapshot(st: NavState, pos: LatLon) {
        if (!ble.isReady) return

        // 去重：值没变就不发（省电、省带宽）
        val key = "${st.state}|${st.maneuverId}|${st.distanceToManeuverM}|${st.remainingDistanceM}"
        if (key == lastSentKey) return
        lastSentKey = key

        var flags = 0
        if (destination != null) flags = flags or Proto.SNAP_HAS_DESTINATION
        if (st.hasFix) flags = flags or Proto.SNAP_HAS_FIX
        if (st.offRoute) flags = flags or Proto.SNAP_OFF_ROUTE
        if (st.hasNextManeuver) flags = flags or Proto.SNAP_HAS_NEXT_MANEUVER
        if (route != null) {
            flags = flags or Proto.SNAP_HAS_ROUTE_VIEW
        }

        val routeToken = routeTokenOf(route)
        val headingCdeg = (headingDeg * 100).roundToInt().coerceIn(0, 35999)
        val accDm = lastLoc?.let { if (it.hasAccuracy()) (it.accuracy * 10).roundToInt() else 0 } ?: 0

        val payload = Proto.navSnapshot(
            state = st.state,
            network = Proto.NET_ONLINE,
            displayPage = Proto.PAGE_NAVIGATION,
            maneuver = st.maneuver,
            traffic = Proto.TRAFFIC_UNKNOWN,
            flags = flags,
            routeToken = routeToken,
            routeGeneration = if (route != null) 1 else 0,
            maneuverId = st.maneuverId,
            distanceToManeuverM = st.distanceToManeuverM.toLong(),
            remainingDistanceM = st.remainingDistanceM.toLong(),
            remainingDurationS = st.remainingDurationS.toLong(),
            routeProgressM = st.routeProgressM.toLong(),
            totalDistanceM = st.totalDistanceM.toLong(),
            speedDeciKph = (speedKph * 10).roundToInt().coerceIn(0, 65535),
            speedLimitKph = 0,
            headingCdeg = headingCdeg,
            accuracyDm = accDm.coerceIn(0, 65535),
            crossTrackDm = st.crossTrackDm,
            roundaboutExit = st.roundaboutExit,
            roadName = st.roadName,
            instruction = st.instruction,
        )
        ble.sendMessage(Proto.MSG_NAV_SNAPSHOT, payload)
    }

    /** FNV-1a 32 位 */
    private fun routeTokenOf(r: Route?): Int {
        if (r == null) return 0
        var h = 2166136261L
        val id = "route-${r.distanceM.roundToInt()}-${r.steps.size}-${r.geometry.size}"
        for (b in id.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 16777619L) and 0xFFFFFFFFL
        }
        val v = h.toInt()
        return if (v == 0) 1 else v
    }

    // ---------------- BLE 回调 ----------------

    private val bleListener = object : BleClient.Listener {
        override fun onBleLog(msg: String) {
            handler.post { listener?.onLog(msg) }
        }

        override fun onScanResult(device: BluetoothDevice, name: String?, rssi: Int) {
            handler.post {
                if (devices.none { it.first.address == device.address }) {
                    devices.add(Triple(device, name, rssi))
                    listener?.onDevices(devices.toList())
                }
            }
        }

        override fun onScanStopped() {
            handler.post { listener?.onLog("扫描已停止") }
        }

        override fun onConnected(deviceName: String?) {
            handler.post { listener?.onStatus("已连接 ${deviceName ?: ""}，握手中…") }
        }

        override fun onHandshakeReady(sessionId: Int, maxFrameSize: Int) {
            handler.post {
                listener?.onStatus("已就绪")
                listener?.onBleReady(sessionId, maxFrameSize)
            }
        }

        override fun onDisconnected() {
            handler.post {
                listener?.onStatus("连接已断开")
                listener?.onLog("BLE 断开")
            }
        }

        override fun onDeviceCommand(cmd: Proto.DeviceCommand) {
            handler.post { listener?.onLog("设备命令: ${cmd.command}") }
        }

        override fun onAck(ackSeq: Int, status: Int) {
            handler.post { listener?.onLog("ACK seq=$ackSeq status=$status") }
        }
    }
}
