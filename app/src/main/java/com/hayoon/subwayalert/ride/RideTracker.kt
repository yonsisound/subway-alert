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
 * 이벤트 기반 위치 추적 (v1.3). 센서(출발/정차) + 위치 보정 + 시간 예비.
 *
 *  - 멈춤 감지 후 [DWELL_MS] 이상 서 있어야 정차 확정 (서행·신호대기 무시)
 *  - 출발 후 최소 시간(60초 또는 예상의 70% 중 짧은 쪽, 예상의 45% 이상) 전 멈춤은 무시
 *  - 기준점(출발·환승·GPS·수동) 이후 예상 시간보다 2역 이상 앞서 세지 않음
 *  - GPS로 다음 역 근처가 잡혀도 바로 도착 처리하지 않고 '접근 중' → 정차 감지 시 도착
 *  - 환승역/도착역 도착 뒤 걷지 않고 다시 출발하면 도착이 아니었던 것으로 되돌림
 *
 * 시간 단위: ms. 순수 로직이라 단위테스트 가능.
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
    var since = 0L; private set            // MOVING: 출발 시각, AT_STATION: 도착 시각
    var estimated = false; private set
    val fired = HashSet<String>()

    // v1.3 상태
    private var pendingStop = -1L          // 멈춤 감지 시각 (확정 대기 중)
    var near = false; private set          // GPS로 다음 역 접근 확인됨
    private var anchorIdx = 0              // 시간표 검증 기준점
    private var anchorT = 0L
    var walkedSinceArrival = false; private set
    private var prevSince = 0L             // 되돌리기용: 도착 직전 구간의 출발 시각
    var hasStepSensor = true               // 걸음 센서가 없으면 걷기 기반 판단 생략

    /** 감지기가 '정지' 상태여야 하는지 (서비스가 센서 상태를 맞출 때 사용) */
    val sensorShouldMove get() = phase == Phase.MOVING && pendingStop < 0

    val targets: List<Int> = stops.indices.filter { stops[it].kind == Stop.Kind.TRANSFER || stops[it].kind == Stop.Kind.DEST }

    fun restore(phase: Phase, idx: Int, since: Long, fired: Collection<String>) {
        this.phase = phase; this.idx = idx.coerceIn(0, stops.size - 1); this.since = since
        this.fired.clear(); this.fired.addAll(fired)
        anchorIdx = this.idx; anchorT = since; pendingStop = -1; near = false
    }

    fun start(now: Long) {
        phase = Phase.WAITING; idx = 0; since = now; fired.clear(); pendingStop = -1; near = false
        anchorIdx = 0; anchorT = now
    }

    private fun name(i: Int) = net.stations[stops[i].station]?.name ?: "?"
    private fun expMs(k: Int) = stops[k].hopSec * 1000L
    private fun anchor(k: Int, t: Long) { anchorIdx = k; anchorT = t }

    // ---------------- 이벤트 ----------------

    fun onWalk(now: Long) { walkedSinceArrival = true }

    /** 열차 출발 감지(또는 수동 '출발') */
    fun onDeparture(now: Long, manual: Boolean = false): List<Alert> {
        when (phase) {
            Phase.WAITING -> { anchor(0, now); return depart(now, false) }
            Phase.MOVING -> {
                if (pendingStop >= 0) {           // 확정 전에 다시 출발 = 서행
                    pendingStop = -1
                    return emptyList()
                }
                if (near) {                       // 다음 역에 서 있었는데 정차를 놓침 → 도착 후 출발
                    val out = arrive(idx + 1, now - 20_000, false).toMutableList()
                    if (phase == Phase.AT_STATION && stops[idx].kind == Stop.Kind.PASS) out += depart(now, false)
                    return out
                }
                return emptyList()
            }
            Phase.AT_STATION -> {
                val s = stops[idx]
                if (s.kind == Stop.Kind.TRANSFER && !manual) {
                    val waited = now - since
                    if (hasStepSensor && !walkedSinceArrival && waited < 180_000) return revertArrival(now)
                    if (!hasStepSensor && waited < 60_000) return emptyList()
                    anchor(idx, now)
                }
                return depart(now, false)
            }
            Phase.FINISHED -> {
                if (!manual && hasStepSensor && !walkedSinceArrival && now - since < 180_000) return revertArrival(now)
                return emptyList()
            }
        }
    }

    /** 멈춤 감지. longStill = 감속 없이 오래 조용했던 경우(신뢰도 낮음) */
    fun onStop(now: Long, longStill: Boolean = false): List<Alert> {
        if (phase != Phase.MOVING || pendingStop >= 0) return emptyList()
        val exp = expMs(idx + 1)
        val gate = when {
            near -> 0L
            longStill -> exp
            else -> maxOf((exp * 0.45).toLong(), minOf(60_000L, (exp * 0.7).toLong()))
        }
        if (now - since < gate) return emptyList()
        if (!near && aheadOfSchedule(idx + 1, now)) return emptyList()
        pendingStop = now
        return emptyList()
    }

    /** 기준점 이후 예상 시간으로 갈 수 있는 역보다 2역 이상 앞서는지 */
    private fun aheadOfSchedule(k: Int, now: Long): Boolean {
        if (k <= anchorIdx + 1) return false
        var cum = 0L; var reach = anchorIdx
        for (j in anchorIdx + 1 until stops.size) {
            cum += expMs(j)
            if (cum <= (now - anchorT)) reach = j else break
        }
        return k > reach + 1
    }

    /** 위치 보정: k번째 정차지점 근처로 확인됨 (앞으로만) */
    fun onSnap(k: Int, now: Long): List<Alert> {
        if (phase == Phase.FINISHED || k <= idx) return emptyList()
        val out = ArrayList<Alert>()
        if (k >= idx + 2 || phase != Phase.MOVING) {
            // k-1 은 지났고 k 로 접근(또는 정차) 중
            idx = k - 1; phase = Phase.MOVING; since = now - expMs(k) / 2; pendingStop = -1
            if (k in targets) preAlert(k)?.let { out += it }
        }
        near = true
        anchor(k - 1, now - expMs(k) / 2)
        if (k in targets) nearAlert(k)?.let { out += it }
        return out
    }

    /** 수동으로 현재 역 지정 */
    fun setStation(k: Int, now: Long): List<Alert> {
        pendingStop = -1; near = false
        anchor(k, now)
        if (k < idx) {
            fired.removeAll { key -> key.drop(3).toIntOrNull()?.let { it > k } == true || key.drop(4).toIntOrNull()?.let { it > k } == true }
            idx = k; phase = if (stops[k].kind == Stop.Kind.DEST) Phase.FINISHED else Phase.AT_STATION; since = now
            walkedSinceArrival = true
            return emptyList()
        }
        if (k == idx) { if (phase == Phase.MOVING) { phase = Phase.AT_STATION; since = now }; return emptyList() }
        return arrive(k, now, false).also { walkedSinceArrival = true }
    }

    /** 주기 점검 (2초마다): 정차 확정, 시간 예비 */
    fun tick(now: Long): List<Alert> {
        when (phase) {
            Phase.MOVING -> {
                if (pendingStop >= 0 && now - pendingStop >= DWELL_MS) {
                    val t = pendingStop; pendingStop = -1
                    return arrive(idx + 1, t, false)
                }
                val exp = expMs(idx + 1)
                if (near && pendingStop < 0 && now - since > exp * 1.3 + 30_000) return arrive(idx + 1, now, true)
                if (!near && pendingStop < 0 && now - since > exp * 1.8 + 45_000) {
                    val out = arrive(idx + 1, since + exp, true).toMutableList()
                    if (phase == Phase.AT_STATION && stops[idx].kind == Stop.Kind.PASS) out += depart(since + 30_000, true)
                    return out
                }
            }
            Phase.AT_STATION -> {
                if (stops[idx].kind == Stop.Kind.PASS && now - since > 90_000) return depart(since + 30_000, true)
            }
            else -> {}
        }
        return emptyList()
    }

    // ---------------- 전이 ----------------

    private fun depart(now: Long, estimated: Boolean): List<Alert> {
        if (idx >= stops.size - 1) return emptyList()
        phase = Phase.MOVING; since = now; this.estimated = estimated; near = false; pendingStop = -1
        val out = ArrayList<Alert>()
        if (idx + 1 in targets) preAlert(idx + 1)?.let { out += it }
        return out
    }

    private fun arrive(k: Int, now: Long, estimated: Boolean): List<Alert> {
        val out = ArrayList<Alert>()
        prevSince = since
        idx = k; since = now; this.estimated = estimated; near = false; pendingStop = -1
        walkedSinceArrival = false
        val s = stops[k]
        phase = if (s.kind == Stop.Kind.DEST) Phase.FINISHED else Phase.AT_STATION
        if (k in targets) arrAlert(k)?.let { out += it }
        return out
    }

    /** 환승/도착역이라 했는데 걷지 않고 열차가 다시 출발 → 아직 그 역이 아니었음 */
    private fun revertArrival(now: Long): List<Alert> {
        val k = idx
        idx = k - 1; phase = Phase.MOVING; since = prevSince; pendingStop = -1; near = false
        fired.remove("arr$k")
        anchor(idx, prevSince)
        return emptyList()
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

    private fun nearAlert(t: Int): Alert? {
        if ("pre$t" !in fired) return preAlert(t)
        val key = "near$t"; if (key in fired) return null; fired += key
        val n = name(t)
        val what = if (stops[t].kind == Stop.Kind.DEST) "내리실" else "갈아타실"
        return Alert(key, "곧 $n 도착", "$what 준비를 하세요.", "곧 ${n}에 도착합니다.")
    }

    private fun arrAlert(t: Int): Alert? {
        val key = "arr$t"; if (key in fired) return null; fired += key
        val n = name(t)
        return if (stops[t].kind == Stop.Kind.DEST)
            Alert(key, "$n 도착 — 하차", "역 이름을 확인하고 내리세요.", "${n}에 도착했습니다. 역 이름을 확인하고 내리세요.")
        else {
            val nl = Network.shortName(route.legs[stops[t].legIndex + 1].line)
            Alert(key, "$n 도착 — 환승", "역 이름을 확인하고 ${nl}${ro(nl)} 갈아타세요.", "${n}입니다. 역 이름을 확인하고 ${nl}${ro(nl)} 갈아타세요.")
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
        const val DWELL_MS = 8_000L

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
