package com.motonav.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 经纬度（WGS84） */
data class LatLon(val lat: Double, val lon: Double)

/** 一个转向步骤 */
data class RouteStep(
    val type: String,
    val modifier: String?,
    val name: String,
    val distanceM: Double,
    val location: LatLon,
)

/** 完整路线 */
data class Route(
    val distanceM: Double,
    val durationS: Double,
    val steps: List<RouteStep>,
    val geometry: List<LatLon>,
) {
    val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * 路线服务：OSRM（开源路线引擎，免费、无需 Key）
 *   公共服务器：https://router.project-osrm.org
 * 地址搜索：Nominatim（OpenStreetMap 地理编码，免费）
 */
object RouteService {

    private const val TAG = "RouteService"

    private const val OSRM_BASE = "https://router.project-osrm.org"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"

    private const val UA = "MotoNav/0.1 (Android; personal use)"

    // ---------------- 地址搜索 ----------------

    /**
     * 把「北京 故宫」这类文本转成坐标。
     * @param near 可选的位置偏置，用于优先返回附近结果
     */
    fun geocode(query: String, near: LatLon? = null): LatLon? {
        try {
            val sb = StringBuilder(NOMINATIM)
            sb.append("?q=").append(URLEncoder.encode(query, "UTF-8"))
            sb.append("&format=json&limit=1&accept-language=zh-CN")
            if (near != null) {
                // viewbox 让他优先返回附近的结果（约 ±1 度）
                val d = 1.0
                sb.append("&viewbox=")
                sb.append(near.lon - d).append(",")
                sb.append(near.lat - d).append(",")
                sb.append(near.lon + d).append(",")
                sb.append(near.lat + d)
                sb.append("&bounded=0")
            }
            val body = httpGet(sb.toString()) ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            return LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble())
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed", e)
            return null
        }
    }

    /** 返回多个候选，方便用户挑选 */
    fun geocodeList(query: String, limit: Int = 5): List<Pair<String, LatLon>> {
        val out = ArrayList<Pair<String, LatLon>>()
        try {
            val url = "$NOMINATIM?q=${URLEncoder.encode(query, "UTF-8")}" +
                    "&format=json&limit=$limit&accept-language=zh-CN"
            val body = httpGet(url) ?: return out
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("display_name", "未命名")
                out.add(name to LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocodeList failed", e)
        }
        return out
    }

    // ---------------- 路线规划 ----------------

    /**
     * 请求路线。返回 null 表示失败。
     * driving profile 为机动车；摩托车可用 motor_scooter（需自建服务器支持）
     */
    fun route(from: LatLon, to: LatLon, profile: String = "driving"): Route? {
        return try {
            val url = "$OSRM_BASE/route/v1/$profile/" +
                    "${from.lon},${from.lat};${to.lon},${to.lat}" +
                    "?overview=full&geometries=geojson&steps=true"
            val body = httpGet(url) ?: return null
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") {
                Log.w(TAG, "OSRM code=${root.optString("code")}")
                return null
            }
            val routes = root.getJSONArray("routes")
            if (routes.length() == 0) return null

            val r = routes.getJSONObject(0)
            val geometry = ArrayList<LatLon>()
            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                geometry.add(LatLon(c.getDouble(1), c.getDouble(0)))  // GeoJSON 是 [lon, lat]
            }

            val steps = ArrayList<RouteStep>()
            val legs = r.getJSONArray("legs")
            for (li in 0 until legs.length()) {
                val leg = legs.getJSONObject(li)
                val st = leg.getJSONArray("steps")
                for (si in 0 until st.length()) {
                    val s = st.getJSONObject(si)
                    val m = s.getJSONObject("maneuver")
                    val loc = m.getJSONArray("location")
                    steps.add(
                        RouteStep(
                            type = m.optString("type", "turn"),
                            modifier = m.optString("modifier", "").ifBlank { null },
                            name = s.optString("name", ""),
                            distanceM = s.optDouble("distance", 0.0),
                            location = LatLon(loc.getDouble(1), loc.getDouble(0)),
                        )
                    )
                }
            }

            Route(
                distanceM = r.optDouble("distance", 0.0),
                durationS = r.optDouble("duration", 0.0),
                steps = steps,
                geometry = geometry,
            )
        } catch (e: Exception) {
            Log.w(TAG, "route failed", e)
            null
        }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $urlStr")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "http failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** 地理计算工具 */
object Geo {

    private const val R = 6371000.0  // 地球半径（米）

    /** 两点间距离（米），Haversine */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 方位角（0..360，正北为 0） */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    /**
     * 点到线段的最近点参数 t（0..1），用于把当前位置投影到路线段上
     * 用平面近似（局部几公里内足够精确）
     */
    fun projectParam(p: LatLon, a: LatLon, b: LatLon): Double {
        val latRad = Math.toRadians(p.lat)
        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * cos(latRad)

        val ax = (a.lon - p.lon) * mPerDegLon
        val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lon - p.lon) * mPerDegLon
        val by = (b.lat - p.lat) * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-9) return 0.0
        val t = -(ax * dx + ay * dy) / len2
        return t.coerceIn(0.0, 1.0)
    }

    /** 点到点距离（米，平面近似） */
    fun distanceFast(p: LatLon, a: LatLon): Double {
        val latRad = Math.toRadians(p.lat)
        val dx = (a.lon - p.lon) * 111320.0 * cos(latRad)
        val dy = (a.lat - p.lat) * 111320.0
        return sqrt(dx * dx + dy * dy)
    }
}
