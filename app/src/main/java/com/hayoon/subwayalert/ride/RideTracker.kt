package com.hayoon.subwayalert.ride

import com.hayoon.subwayalert.route.Network
import com.hayoon.subwayalert.route.Route

/** 경로 위의 정차 지점 하나. hopSec = 이전 정차지점에서 오는 예상 시간 */
data class Stop(val station: Int, val legIndex: Int, val hopSec: Int, val kind: Kind) {
    enum class Kind { ORIGIN, PASS, TRANSFER, DEST }
}

data class Alert(val key: String, val title: String, val text: String, val speech: String)

enum class Phase { WAITING, MOVING, AT_STATION, FINISHED }

data class RideStatus(
    val phase: Phase,
    val current: Int,            // 정차 중이면 그 역, 이동 중이면 방금 출발한 역 (stops 인덱스)
    val nextTarget: Int?,
    val stopsToTarget: Int,
    val secToTarget: Int,
    val atTransfer: Boolean,
) {
    val finished get() = phase == Phase.FINISHED
    val waiting get() = phase == Phase.WAITING || (phase == Phase.AT_STATION && atTransfer)
}

/**
 * 이벤트 기반 위치 추적. 센서(출발/정차) + 위치 보정 + 시간 예비.
 * 시간 단위: ms (now 인자). 순수 로직이라 단위테스트 가능.
 */
class RideTracker(val net: Network, val route: Route) {
    val stops: List<Stop>

    init {
        val list = ArrayList<Stop>()
        route.legs.forEachIndexed { li, leg ->
            if (li == 0) list.add(Stop(leg.from, 0, 0, Stop.Kind.ORIGIN))
            for (k in 1 until leg.stations.size) {
                val last = k == leg.stations.size - 1
                val kind = when {
                    !last -> Stop.Kind.PASS
                    li == route.legs.size - 1 -> Stop.Kind.DEST
                    else -> Stop.Kind.TRANSFER
                }
                list.add(Stop(leg.stations[k], li, leg.hopSecs[k - 1], kind))
            }
        }
        stops = list
    }

    var phase = Phase.WAITING; private set
    var idx = 0; private set
    var since = 0L; private set          // 현재 phase 시작 시각
    var estimated = false; private set    // 마지막 진행이 시간 추정이었는지
    val fired = HashSet<String>()

    val targets: List<Int> = stops.indices.filter { stops[it].kind == Stop.Kind.TRANSFER || stops[it].kind == Stop.Kind.DEST }

    fun restore(phase: Phase, idx: Int, since: Long, fired: Collection<String>) {
        this.phase = phase; this.idx = idx.coerceIn(0, stops.size - 1); this.since = since
        this.fired.clear(); this.fired.addAll(fired)
    }

    fun start(now: Long) { phase = Phase.WAITING; idx = 0; since = now; fired.clear() }

    private fun name(i: Int) = net.stations[stops[i].station]?.name ?: "?"

    // ---------------- 이벤트 ----------------

    /** 열차 출발 감지(또는 수동 '출발') */
    fun onDeparture(now: Long, manual: Boolean = false): List<Alert> {
        if (phase == Phase.MOVING || phase == Phase.FINISHED) return emptyList()
        if (phase == Phase.AT_STATION && stops[idx].kind == Stop.Kind.TRANSFER && !manual && now - since < 60_000) {
            return emptyList()   // 환승역 도착 직후 출발 감지는 같은 열차의 재출발일 가능성 → 1분은 무시
        }
        return depart(now, estimated = false)
    }

    /** 정차 감지. longStill = 감속 없이 오래 조용했던 경우(신뢰도 낮음) */
    fun onStop(now: Long, longStill: Boolean = false): List<Alert> {
        if (phase != Phase.MOVING) return emptyList()
        val exp = stops[idx + 1].hopSec * 1000L
        // 감속 없이 오래 멈춘 경우는 신호대기일 수 있어 예상 시간이 다 지난 뒤에만 인정
        val ratio = if (longStill) 1.0 else 0.45
        if (now - since < exp * ratio) return emptyList()   // 너무 이르면 터널 내 신호대기로 간주
        return arrive(idx + 1, now, estimated = false)
    }

    /** 위치 보정: k번째 정차지점 근처로 확인됨 (앞으로만) */
    fun onSnap(k: Int, now: Long): List<Alert> {
        if (phase == Phase.FINISHED) return emptyList()
        if (k <= idx) return emptyList()
        if (phase == Phase.MOVING && k == idx + 1 && now - since < stops[k].hopSec * 300L) return emptyList()
        return arrive(k, now, estimated = false)
    }

    /** 수동으로 현재 역 지정 */
    fun setStation(k: Int, now: Long): List<Alert> {
        if (k < idx) { // 뒤로 돌리면 그 뒤 알림은 다시 울릴 수 있게
            fired.removeAll { key -> key.drop(3).toIntOrNull()?.let { it > k } == true }
            idx = k; phase = if (stops[k].kind == Stop.Kind.DEST) Phase.FINISHED else Phase.AT_STATION; since = now
            return emptyList()
        }
        if (k == idx) { if (phase == Phase.MOVING) { phase = Phase.AT_STATION; since = now }; return emptyList() }
        return arrive(k, now, estimated = false)
    }

    /** 시간 예비: 센서가 놓쳤을 때 */
    fun tick(now: Long): List<Alert> {
        when (phase) {
            Phase.MOVING -> {
                val exp = stops[idx + 1].hopSec * 1000L
                if (now - since > exp * 1.8 + 45_000) {
                    // 정차를 놓친 것으로 보고 다음 역 도착 → 곧바로 출발한 것으로 처리 (경유역만)
                    val out = arrive(idx + 1, since + exp, estimated = true).toMutableList()
                    if (phase == Phase.AT_STATION && stops[idx].kind == Stop.Kind.PASS) out += depart(since + 30_000, estimated = true)
                    return out
                }
            }
            Phase.AT_STATION -> {
                // 일반 역에서 출발 감지를 놓침 → 90초 지나면 출발한 걸로
                if (stops[idx].kind == Stop.Kind.PASS && now - since > 90_000) return depart(since + 30_000, estimated = true)
            }
            else -> {}
        }
        return emptyList()
    }

    // ---------------- 전이 ----------------

    private fun depart(now: Long, estimated: Boolean): List<Alert> {
        if (idx >= stops.size - 1) return emptyList()
        phase = Phase.MOVING; since = now; this.estimated = estimated
        val out = ArrayList<Alert>()
        val next = idx + 1
        if (next in targets) preAlert(next)?.let { out += it }
        return out
    }

    private fun arrive(k: Int, now: Long, estimated: Boolean): List<Alert> {
        val out = ArrayList<Alert>()
        // 건너뛴 대상들의 도착 알림은 생략, 도착한 역만 알림
        idx = k; since = now; this.estimated = estimated
        val s = stops[k]
        phase = if (s.kind == Stop.Kind.DEST) Phase.FINISHED else Phase.AT_STATION
        if (k in targets) arrAlert(k)?.let { out += it }
        // 이미 다음 대상의 바로 전 역이면(위치로 점프한 경우) 출발 시 사전 알림이 나감
        return out
    }

    private fun preAlert(t: Int): Alert? {
        val key = "pre$t"; if (key in fired) return null; fired += key
        val n = name(t)
        return if (stops[t].kind == Stop.Kind.DEST)
            Alert(key, "다음 역 $n — 하차 준비", "다음 역에서 내리세요.", "다음 역은 ${n}입니다. 내리실 준비를 하세요.")
        else {
            val nl = Network.shortName(route.legs[stops[t].legIndex + 1].line)
            Alert(key, "다음 역 $n — 환승 준비", "다음 역에서 ${nl}${ro(nl)} 갈아타세요.", "다음 역은 ${n}입니다. ${nl}${ro(nl)} 갈아탈 준비를 하세요.")
        }
    }

    private fun arrAlert(t: Int): Alert? {
        val key = "arr$t"; if (key in fired) return null; fired += key
        val n = name(t)
        return if (stops[t].kind == Stop.Kind.DEST)
            Alert(key, "$n 도착 — 하차", "목적지에 도착했어요.", "${n}에 도착했습니다. 내리세요.")
        else {
            val nl = Network.shortName(route.legs[stops[t].legIndex + 1].line)
            Alert(key, "$n 도착 — 환승", "${nl}${ro(nl)} 갈아타세요.", "${n}입니다. ${nl}${ro(nl)} 갈아타세요.")
        }
    }

    // ---------------- 상태 ----------------

    fun status(now: Long): RideStatus {
        val target = targets.firstOrNull { it > idx || (it == idx && phase == Phase.FINISHED) }
        var sec = 0
        if (target != null && target > idx) {
            for (k in idx + 1..target) sec += stops[k].hopSec
            if (phase == Phase.MOVING) sec -= ((now - since) / 1000).toInt()
        }
        return RideStatus(phase, idx, target, if (target == null) 0 else target - idx, maxOf(0, sec),
            phase == Phase.AT_STATION && stops[idx].kind == Stop.Kind.TRANSFER)
    }

    /** 좌표 보정: 현재 이후 3정거장 안의 경로 역 근처면 인덱스 */
    fun snap(lat: Double, lon: Double, accuracy: Float): Int? {
        if (accuracy > 400f) return null
        var best: Int? = null; var bestD = Double.MAX_VALUE
        val hi = minOf(stops.size - 1, idx + 3)
        for (k in idx..hi) {
            val st = net.stations[stops[k].station] ?: continue
            val d = distM(lat, lon, st.lat, st.lon)
            if (d < bestD) { bestD = d; best = k }
        }
        return if (bestD <= 250.0 + accuracy) best else null
    }

    companion object {
        /** 받침에 맞는 조사 (으로/로) */
        fun ro(w: String): String {
            val c = w.lastOrNull() ?: return "로"
            if (c !in '가'..'힣') return "로"
            val jong = (c - '가') % 28
            return if (jong == 0 || jong == 8) "로" else "으로"
        }

        fun distM(a1: Double, o1: Double, a2: Double, o2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(a2 - a1); val dLon = Math.toRadians(o2 - o1)
            val h = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(h))
        }
    }
}
