package com.hayoon.subwayalert.ride

import com.hayoon.subwayalert.route.Network
import com.hayoon.subwayalert.route.Route

/** 경로 위의 정차 지점 하나 */
data class Stop(
    val station: Int,
    val legIndex: Int,
    val arriveAt: Int,          // 탑승 시작 기준 도착 예정(초)
    val departAt: Int,          // 출발 예정(초). 환승역이면 다음 열차 출발
    val kind: Kind,
) {
    enum class Kind { ORIGIN, PASS, TRANSFER, DEST }
}

/** 알림 대상(환승역/도착역)마다 '한 정거장 전' 과 '도착' 두 번 */
data class Alert(val key: String, val title: String, val text: String, val strong: Boolean)

data class RideStatus(
    val current: Int,            // stops 인덱스 (지났거나 정차 중인 마지막 역)
    val atStation: Boolean,
    val nextTarget: Int?,        // 다음 환승/도착 stops 인덱스
    val stopsToTarget: Int,
    val secToTarget: Int,
    val waitingTransfer: Boolean,
    val finished: Boolean,
)

/**
 * 시간 기반 위치 추정 + 보정. 순수 로직(안드로이드 의존 없음).
 * elapsed = (now - startMs)/1000 + shiftSec
 */
class RideTracker(val net: Network, val route: Route) {
    val stops: List<Stop>

    init {
        val list = ArrayList<Stop>()
        var t = 0
        route.legs.forEachIndexed { li, leg ->
            if (li == 0) list.add(Stop(leg.from, 0, 0, 0, Stop.Kind.ORIGIN))
            for (k in 1 until leg.stations.size) {
                t += leg.hopSecs[k - 1]
                val last = k == leg.stations.size - 1
                val kind = when {
                    !last -> Stop.Kind.PASS
                    li == route.legs.size - 1 -> Stop.Kind.DEST
                    else -> Stop.Kind.TRANSFER
                }
                val dep = if (kind == Stop.Kind.TRANSFER) t + route.transferSecs[li] else t
                list.add(Stop(leg.stations[k], li, t, dep, kind))
                t = dep
            }
        }
        stops = list
    }

    val targets: List<Int> get() = stops.indices.filter { stops[it].kind == Stop.Kind.TRANSFER || stops[it].kind == Stop.Kind.DEST }

    fun status(elapsed: Int): RideStatus {
        var cur = 0
        for (k in stops.indices) if (stops[k].arriveAt <= elapsed) cur = k
        val s = stops[cur]
        val atStation = elapsed < s.departAt + DWELL
        val target = targets.firstOrNull { it > cur || (it == cur && stops[it].kind == Stop.Kind.DEST) }
        val finished = stops[cur].kind == Stop.Kind.DEST
        val waiting = s.kind == Stop.Kind.TRANSFER && elapsed < s.departAt
        return RideStatus(
            current = cur, atStation = atStation, nextTarget = target,
            stopsToTarget = if (target == null) 0 else (target - cur),
            secToTarget = if (target == null) 0 else maxOf(0, stops[target].arriveAt - elapsed),
            waitingTransfer = waiting, finished = finished,
        )
    }

    /** elapsed 시점까지 울려야 할 알림들 (fired 에 없는 것만) */
    fun dueAlerts(elapsed: Int, fired: Set<String>): List<Alert> {
        val out = ArrayList<Alert>()
        for (ti in targets) {
            val t = stops[ti]
            val name = net.stations[t.station]?.name ?: "?"
            val isDest = t.kind == Stop.Kind.DEST
            val action = if (isDest) "하차" else "환승"
            val nextLine = if (!isDest) Network.shortName(route.legs[t.legIndex + 1].line) else ""
            // 한 정거장 전: 직전 역을 출발한 시점
            val prev = stops[ti - 1]
            val preAt = prev.departAt + DWELL
            val preKey = "pre$ti"
            if (elapsed >= preAt && elapsed < t.arriveAt + 60 && preKey !in fired) {
                out.add(Alert(preKey, "다음 역 $name — ${action} 준비",
                    if (isDest) "다음 역에서 내리세요." else "다음 역에서 $nextLine 으로 갈아타세요.", true))
            }
            val arrKey = "arr$ti"
            if (elapsed >= t.arriveAt && arrKey !in fired) {
                out.add(Alert(arrKey, "$name 도착 — $action",
                    if (isDest) "목적지에 도착했어요." else "$nextLine 으로 갈아타세요.", true))
            }
        }
        return out
    }

    /** 특정 역(stops 인덱스)에 지금 도착했다고 보정할 때의 elapsed */
    fun elapsedForArrival(idx: Int): Int = stops[idx].arriveAt

    /** 환승역에서 다음 열차에 탔다고 보정 */
    fun elapsedForDeparture(idx: Int): Int = stops[idx].departAt

    /** 좌표 보정: 근처 역(경로 위, 현재 추정 이후 3정거장 안)을 찾으면 인덱스 반환 */
    fun snap(lat: Double, lon: Double, accuracy: Float, currentIdx: Int): Int? {
        if (accuracy > 400f) return null
        var best: Int? = null; var bestD = Double.MAX_VALUE
        val lo = maxOf(0, currentIdx - 1); val hi = minOf(stops.size - 1, currentIdx + 3)
        for (k in lo..hi) {
            val st = net.stations[stops[k].station] ?: continue
            val d = distM(lat, lon, st.lat, st.lon)
            if (d < bestD) { bestD = d; best = k }
        }
        val limit = 250.0 + accuracy
        return if (bestD <= limit) best else null
    }

    companion object {
        const val DWELL = 30
        fun distM(a1: Double, o1: Double, a2: Double, o2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(a2 - a1); val dLon = Math.toRadians(o2 - o1)
            val h = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(h))
        }
    }
}
