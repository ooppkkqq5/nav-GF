package com.motonav.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity(), NavCore.Listener {

    private lateinit var status: TextView
    private lateinit var navInfo: TextView
    private lateinit var logView: TextView
    private lateinit var etDestination: EditText
    private lateinit var deviceList: ListView
    private lateinit var candidateList: ListView

    private var deviceAdapter: ArrayAdapter<String>? = null
    private var candidateAdapter: ArrayAdapter<String>? = null

    private var deviceEntries = mutableListOf<Triple<BluetoothDevice, String?, Int>>()
    private var candidates = mutableListOf<Pair<String, LatLon>>()

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        navInfo = findViewById(R.id.navInfo)
        logView = findViewById(R.id.log)
        etDestination = findViewById(R.id.etDestination)
        deviceList = findViewById(R.id.deviceList)
        candidateList = findViewById(R.id.candidateList)

        NavCore.init(this)
        NavCore.listener = this

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        deviceList.adapter = deviceAdapter
        deviceList.setOnItemClickListener { _, _, pos, _ ->
            val d = deviceEntries.getOrNull(pos) ?: return@setOnItemClickListener
            appendLog("连接 ${d.first.address}")
            NavCore.connect(d.first)
        }

        candidateAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        candidateList.adapter = candidateAdapter
        candidateList.setOnItemClickListener { _, _, pos, _ ->
            val c = candidates.getOrNull(pos) ?: return@setOnItemClickListener
            appendLog("选中：${c.first}")
            NavCore.selectDestination(c.second, c.first)
            if (NavCore.position == null) {
                toast("还没定位，先到空旷处等 GPS")
            }
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            if (checkPerms()) {
                deviceEntries.clear()
                deviceAdapter?.clear()
                NavCore.startScan()
            }
        }
        findViewById<Button>(R.id.btnStopScan).setOnClickListener { NavCore.stopScan() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            val t = etDestination.text.toString().trim()
            if (t.isEmpty()) { toast("请输入目的地"); return@setOnClickListener }
            if (!checkPerms()) return@setOnClickListener
            NavCore.searchDestination(t)
        }
        findViewById<Button>(R.id.btnNavigate).setOnClickListener {
            if (!checkPerms()) return@setOnClickListener
            NavService.start(this)
            NavCore.startNav()
        }
        findViewById<Button>(R.id.btnEnd).setOnClickListener {
            NavCore.stopNav()
            NavService.stop(this)
            navInfo.text = ""
            appendLog("导航结束")
        }

        if (!checkPerms()) requestPerms()
        NavCore.startLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (NavCore.listener === this) NavCore.listener = null
    }

    // ---------------- 权限 ----------------

    private fun requiredPerms(): List<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun checkPerms(): Boolean {
        val missing = requiredPerms().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPerms()
            return false
        }
        return true
    }

    private fun requestPerms() {
        ActivityCompat.requestPermissions(this, requiredPerms().toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val denied = grantResults.count { it != PackageManager.PERMISSION_GRANTED }
            if (denied == 0) {
                appendLog("权限已授予")
                NavCore.startLocation()
            } else {
                toast("有 $denied 项权限被拒绝，功能会受限")
            }
        }
    }

    // ---------------- NavCore 回调 ----------------

    override fun onLog(msg: String) {
        runOnUiThread { appendLog(msg) }
    }

    override fun onStatus(st: String) {
        runOnUiThread { status.text = st }
    }

    override fun onNavState(s: NavState) {
        runOnUiThread {
            val km = "%.2f".format(s.remainingDistanceM / 1000.0)
            val min = s.remainingDurationS / 60
            navInfo.text = buildString {
                append("下一个：").append(maneuverLabel(s.maneuver)).append("\n")
                append("距离转弯：").append(s.distanceToManeuverM).append(" m\n")
                append("剩余：").append(km).append(" km  /  ").append(min).append(" 分钟\n")
                append("当前道路：").append(s.roadName.ifBlank { "—" }).append("\n")
                append(s.instruction)
                if (s.offRoute) append("\n⚠ 已偏离路线，建议重算")
            }
        }
    }

    override fun onDevices(found: List<Triple<BluetoothDevice, String?, Int>>) {
        runOnUiThread {
            deviceEntries = found.toMutableList()
            deviceAdapter?.clear()
            found.forEach { (d, n, r) ->
                deviceAdapter?.add("${n ?: "未知设备"}   ${d.address}   ${r}dBm")
            }
            deviceAdapter?.notifyDataSetChanged()
        }
    }

    override fun onCandidates(list: List<Pair<String, LatLon>>) {
        runOnUiThread {
            candidates = list.toMutableList()
            candidateAdapter?.clear()
            list.forEach { (n, _) ->
                candidateAdapter?.add(n)
            }
            candidateAdapter?.notifyDataSetChanged()
            if (list.isNotEmpty()) appendLog("找到 ${list.size} 个候选，点一个选为终点")
        }
    }

    override fun onBleReady(sessionId: Int, maxFrameSize: Int) {
        runOnUiThread {
            appendLog("握手完成 session=$sessionId frame=$maxFrameSize")
        }
    }

    // ---------------- 工具 ----------------

    private fun appendLog(s: String) {
        logLines.addLast(s)
        while (logLines.size > 40) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun maneuverLabel(m: Int): String = when (m) {
        NavEngine.M_ARRIVE -> "到达"
        NavEngine.M_ROUNDABOUT -> "环岛"
        NavEngine.M_EXIT -> "驶出"
        NavEngine.M_SHARP_LEFT -> "急左转"
        NavEngine.M_LEFT -> "左转"
        NavEngine.M_SLIGHT_LEFT -> "稍向左"
        NavEngine.M_UTURN_LEFT -> "掉头"
        NavEngine.M_SHARP_RIGHT -> "急右转"
        NavEngine.M_RIGHT -> "右转"
        NavEngine.M_SLIGHT_RIGHT -> "稍向右"
        NavEngine.M_CONTINUE -> "直行"
        else -> "—"
    }
}
