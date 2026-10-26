package com.motonav.app

import kotlin.math.roundToInt

/** 由引擎算出的、准备发给圆屏的状态 */
data class NavState(
    val state: Int,                 // Proto.NAV_*
    val maneuver: Int,              // Proto maneuver 枚举 0..12
    val maneuverId: Int,
    val distanceToManeuverM: Int,
    val remainingDistanceM: Int,
    val remainingDurationS: Int,
    val routeProgressM: Int,
    val totalDistanceM: Int,
    val crossTrackDm: Int,          // 0.1m
    val offRoute: Boolean,
    val hasFix: Boolean,
    val roundaboutExit: Int,
    val roadName: String,
    val instruction: String,
    val hasNextManeuver: Boolean,
)

/**
 * 导航引擎：把「当前位置 + 路线」变成「下个转弯 + 剩余距离」。
 *
 * 只做纯计算，不碰 BLE / UI，方便单测和替换。
 */
class NavEngine {

    companion object {
        /** 偏离路线阈值（米），超过即判偏航 */
        const val OFF_ROUTE_M = 60.0
        /** 连续多少次超阈值才真的判偏航，避免抖动 */
        const val OFF_ROUTE_SAMPLES = 3
        /** 到达判定：距终点小于这个距离 */
        const val ARRIVE_M = 25.0

        // ManeuverType 枚举（与固件 nav_types.hpp 一致）
        const val M_UNKNOWN = 0
        const val M_CONTINUE = 1
        const val M_SLIGHT_LEFT = 2
        const val M_LEFT = 3
        const val M_SHARP_LEFT = 4
        const val M_UTURN_LEFT = 5
        const val M_SLIGHT_RIGHT = 6
        const val M_RIGHT = 7
        const val M_SHARP_RIGHT = 8
        const val M_UTURN_RIGHT = 9
        const val M_ROUNDABOUT = 10
        const val M_EXIT = 11
        const val M_ARRIVE = 12
    }

    private var route: Route? = null

    /** 几何累积距离 */
    private var cumDist = DoubleArray(0)
    /** 每个 step 在路线上的偏移 */
    private var stepOffset = DoubleArray(0)
    /** 每个 step 对应的 maneuver 枚举 */
    private var stepManeuver = IntArray(0)
    /** 每个 step 的 instruction 文案 */
    private var stepInstruction = arrayOf<String>()

    private var offRouteCount = 0
    private var lastProgress = 0.0

    var arrived = false
        private set

    fun setRoute(r: Route) {
        route = r
        arrived = false
        offRouteCount = 0
        lastProgress = 0.0

        val g = r.geometry
        cumDist = DoubleArray(g.size)
        for (i in 1 until g.size) {
            cumDist[i] = cumDist[i - 1] + Geo.distance(g[i - 1], g[i])
        }

        // 把每个 step 投影到几何上，求路线偏移
        stepOffset = DoubleArray(r.steps.size)
        stepManeuver = IntArray(r.steps.size)
        stepInstruction = Array(r.steps.size) { "" }
        for (i in r.steps.indices) {
            val s = r.steps[i]
            stepOffset[i] = offsetOf(s.location)
            stepManeuver[i] = mapManeuver(s)
            stepInstruction[i] = instructionOf(s)
        }
    }

    fun clear() {
        route = null
        arrived = false
        offRouteCount = 0
        lastProgress = 0.0
    }

    val hasRoute: Boolean get() = route != null

    val totalDistanceM: Double get() = route?.let { cumDist.lastOrNull() ?: 0.0 } ?: 0.0

    /** 给定位置，求在路线上的进度（米） */
    private fun offsetOf(p: LatLon): Double {
        val r = route ?: return 0.0
        val g = r.geometry
        if (g.size < 2) return 0.0
        var best = Double.MAX_VALUE
        var bestOffset = 0.0
        for (i in 0 until g.size - 1) {
            val a = g[i]
            val b = g[i + 1]
            val t = Geo.projectParam(p, a, b)
            val proj = LatLon(
                a.lat + (b.lat - a.lat) * t,
                a.lon + (b.lon - a.lon) * t,
            )
            val d = Geo.distanceFast(p, proj)
            if (d < best) {
                best = d
                bestOffset = cumDist[i] + (cumDist[i + 1] - cumDist[i]) * t
            }
        }
        return bestOffset
    }

    /**
     * 计算当前导航状态
     * @param pos 当前位置
     * @param speedKph 速度
     */
    fun update(pos: LatLon, speedKph: Double): NavState {
        val r = route ?: return noRouteState()
        val g = r.geometry
        if (g.size < 2) return noRouteState()

        // 找最近的几何段，同时得到进度和横向偏差
        var best = Double.MAX_VALUE
        var progress = 0.0
        for (i in 0 until g.size - 1) {
            val a = g[i]
            val b = g[i + 1]
            val t = Geo.projectParam(pos, a, b)
            val proj = LatLon(
                a.lat + (b.lat - a.lat) * t,
                a.lon + (b.lon - a.lon) * t,
            )
            val d = Geo.distanceFast(pos, proj)
            if (d < best) {
                best = d
                progress = cumDist[i] + (cumDist[i + 1] - cumDist[i]) * t
            }
        }

        // 进度不能倒退太多（掉头/漂移保护），但允许小幅回退
        if (progress < lastProgress - 30.0) progress = lastProgress
        lastProgress = progress

        val crossTrackM = best
        val offNow = crossTrackM > OFF_ROUTE_M
        offRouteCount = if (offNow) offRouteCount + 1 else 0
        val offRoute = offRouteCount >= OFF_ROUTE_SAMPLES

        // 找下一个 step
        val total = totalDistanceM
        var idx = stepOffset.indexOfFirst { it > progress + 3.0 }
        if (idx < 0) idx = stepOffset.lastIndex.coerceAtLeast(0)

        val step = r.steps.getOrNull(idx)
        val distToManeuver = (stepOffset.getOrElse(idx) { total } - progress).coerceAtLeast(0.0)
        val remaining = (total - progress).coerceAtLeast(0.0)

        if (remaining < ARRIVE_M) arrived = true

        val remainingTime = if (total > 1.0) {
            (r.durationS * remaining / total).roundToInt()
        } else 0

        return NavState(
            state = when {
                arrived -> Proto.NAV_ARRIVED
                offRoute -> Proto.NAV_REROUTING
                else -> Proto.NAV_NAVIGATING
            },
            maneuver = stepManeuver.getOrElse(idx) { M_UNKNOWN },
            maneuverId = idx + 1,
            distanceToManeuverM = distToManeuver.roundToInt(),
            remainingDistanceM = remaining.roundToInt(),
            remainingDurationS = remainingTime,
            routeProgressM = progress.roundToInt(),
            totalDistanceM = total.roundToInt(),
            crossTrackDm = (crossTrackM * 10).roundToInt().coerceAtMost(65535),
            offRoute = offRoute,
            hasFix = true,
            roundaboutExit = 0,
            roadName = step?.name?.take(63) ?: "",
            instruction = stepInstruction.getOrElse(idx) { "" }.take(95),
            hasNextManeuver = step != null,
        )
    }

    private fun noRouteState() = NavState(
        state = Proto.NAV_IDLE,
        maneuver = M_UNKNOWN,
        maneuverId = 0,
        distanceToManeuverM = 0,
        remainingDistanceM = 0,
        remainingDurationS = 0,
        routeProgressM = 0,
        totalDistanceM = 0,
        crossTrackDm = 0,
        offRoute = false,
        hasFix = true,
        roundaboutExit = 0,
        roadName = "",
        instruction = "",
        hasNextManeuver = false,
    )

    // ---------------- OSRM → 协议枚举 ----------------

    private fun mapManeuver(s: RouteStep): Int {
        val mod = s.modifier?.lowercase() ?: ""
        return when (s.type.lowercase()) {
            "depart" -> M_CONTINUE
            "arrive" -> M_ARRIVE
            "roundabout", "rotary", "roundabout turn" -> M_ROUNDABOUT
            "exit" -> M_EXIT
            "new name", "continue", "notification" -> M_CONTINUE
            "end of road" -> when {
                mod.contains("left") -> M_LEFT
                mod.contains("right") -> M_RIGHT
                else -> M_CONTINUE
            }
            "merge", "fork", "on ramp", "off ramp" -> when {
                mod.contains("slight left") -> M_SLIGHT_LEFT
                mod.contains("slight right") -> M_SLIGHT_RIGHT
                mod.contains("left") -> M_LEFT
                mod.contains("right") -> M_RIGHT
                else -> M_CONTINUE
            }
            "turn" -> when {
                mod.contains("uturn") || mod.contains("u-turn") -> M_UTURN_LEFT
                mod.contains("sharp left") -> M_SHARP_LEFT
                mod.contains("slight left") -> M_SLIGHT_LEFT
                mod.contains("sharp right") -> M_SHARP_RIGHT
                mod.contains("slight right") -> M_SLIGHT_RIGHT
                mod.contains("left") -> M_LEFT
                mod.contains("right") -> M_RIGHT
                else -> M_CONTINUE
            }
            else -> M_UNKNOWN
        }
    }

    private fun instructionOf(s: RouteStep): String {
        val name = s.name.ifBlank { "当前道路" }
        return when (mapManeuver(s)) {
            M_ARRIVE -> "到达目的地附近"
            M_ROUNDABOUT -> "进入环岛，走 $name"
            M_EXIT -> "驶出 $name"
            M_SHARP_LEFT -> "急左转进入 $name"
            M_LEFT -> "左转进入 $name"
            M_SLIGHT_LEFT -> "稍向左前方进入 $name"
            M_UTURN_LEFT -> "掉头进入 $name"
            M_SHARP_RIGHT -> "急右转进入 $name"
            M_RIGHT -> "右转进入 $name"
            M_SLIGHT_RIGHT -> "稍向右前方进入 $name"
            M_UTURN_RIGHT -> "掉头进入 $name"
            M_CONTINUE -> "沿 $name 直行"
            else -> "沿 $name 行驶"
        }
    }
}
