package com.hayoon.subwayalert.route

import java.util.PriorityQueue

enum class Mode { MIN_TIME, MIN_TRANSFER }

/** 한 노선을 타고 가는 구간. stations[0]=승차역, last=하차역 */
data class Leg(val line: String, val stations: List<Int>, val hopSecs: List<Int>) {
    val from get() = stations.first()
    val to get() = stations.last()
    val rideSec get() = hopSecs.sum()
    val stops get() = stations.size - 1
}

data class Route(val legs: List<Leg>, val transferSecs: List<Int>) {
    val transfers get() = legs.size - 1
    val totalSec get() = legs.sumOf { it.rideSec } + transferSecs.sum()
    val origin get() = legs.first().from
    val dest get() = legs.last().to
    /** 환승역 목록 (legs[i].to == legs[i+1].from) */
    val transferStations get() = legs.dropLast(1).map { it.to }
    val signature get() = legs.joinToString("|") { "${it.line}:${it.from}>${it.to}" }
    fun cost(mode: Mode): Long = when (mode) {
        Mode.MIN_TIME -> totalSec.toLong()
        Mode.MIN_TRANSFER -> transfers * 1_000_000L + totalSec
    }
}

/** 환승역 변경 후보 */
data class TransferOption(val station: Int, val route: Route)

class Router(private val net: Network) {

    private data class State(val st: Int, val line: String, val prev: Int = -1)
    private data class Item(val cost: Long, val state: State)

    /**
     * 최단 경로. startLine: 출발역에서 반드시 탈 노선, endLine: 도착역에 반드시 이 노선으로 도착.
     * banned: 환승 금지역.
     */
    fun shortest(
        from: Int, to: Int, mode: Mode,
        startLine: String? = null, endLine: String? = null,
        banned: Set<Int> = emptySet(),
    ): Route? {
        if (from == to) return null
        val penalty: (Int) -> Long = { st ->
            if (mode == Mode.MIN_TRANSFER) 1_000_000L + net.transferAt(st) else net.transferAt(st).toLong()
        }
        val dist = HashMap<State, Long>()
        val prev = HashMap<State, State>()
        val pq = PriorityQueue<Item>(compareBy { it.cost })
        val startLines = startLine?.let { setOf(it) } ?: net.linesAt[from].orEmpty()
        for (l in startLines) {
            val s = State(from, l, -1); dist[s] = 0; pq.add(Item(0, s))
        }
        var goal: State? = null
        while (pq.isNotEmpty()) {
            val (c, s) = pq.poll()!!
            if (c > (dist[s] ?: Long.MAX_VALUE)) continue
            if (s.st == to && (endLine == null || s.line == endLine)) { goal = s; break }
            // 같은 노선으로 이동
            for (h in net.adj[s.st].orEmpty()) {
                if (h.line != s.line || h.to == s.prev) continue
                if (!net.canPass(s.line, s.prev, s.st, h.to)) continue
                relax(State(h.to, h.line, s.st), c + h.sec, s, dist, prev, pq)
            }
            // 환승 (출발역/도착역에서는 의미 없음)
            if (s.st != from && s.st != to && s.st !in banned) {
                if (s.prev >= 0 && "${s.line}|${s.st}" in net.branchJunctions)
                    relax(State(s.st, s.line, -1), c + penalty(s.st), s, dist, prev, pq)
                for (l in net.linesAt[s.st].orEmpty()) {
                    if (l == s.line) continue
                    relax(State(s.st, l, -1), c + penalty(s.st), s, dist, prev, pq)
                }
            }
        }
        val g = goal ?: return null
        val path = ArrayList<State>()
        var cur: State? = g
        while (cur != null) { path.add(cur); cur = prev[cur] }
        path.reverse()
        return toRoute(path.map { it.st to it.line })
    }

    private fun relax(n: State, nc: Long, from: State, dist: HashMap<State, Long>, prev: HashMap<State, State>, pq: PriorityQueue<Item>) {
        if (nc < (dist[n] ?: Long.MAX_VALUE)) { dist[n] = nc; prev[n] = from; pq.add(Item(nc, n)) }
    }

    private fun toRoute(steps: List<Pair<Int, String>>): Route {
        val legs = ArrayList<Leg>()
        val tsecs = ArrayList<Int>()
        var curLine = steps[0].second
        var sts = arrayListOf(steps[0].first)
        var secs = arrayListOf<Int>()
        for (k in 1 until steps.size) {
            val (st, line) = steps[k]
            if (line != curLine || st == sts.last()) { // 환승 (같은 역)
                if (sts.size > 1) { legs.add(Leg(curLine, sts, secs)); tsecs.add(net.transferAt(st)) }
                curLine = line; sts = arrayListOf(st); secs = arrayListOf()
            } else {
                secs.add(net.hopSec(sts.last(), st, line)); sts.add(st)
            }
        }
        if (sts.size > 1) legs.add(Leg(curLine, sts, secs))
        // 첫 구간 없이 환승부터 시작하는 경우 정리
        while (tsecs.size > legs.size - 1) tsecs.removeAt(0)
        return Route(legs, tsecs)
    }

    /** 대안 경로 포함 최대 n개 (모드 기준 정렬) */
    fun alternatives(from: Int, to: Int, mode: Mode, n: Int = 3): List<Route> {
        val found = LinkedHashMap<String, Route>()
        fun add(r: Route?) { if (r != null) found.putIfAbsent(r.signature, r) }
        val best = shortest(from, to, mode) ?: return emptyList()
        add(best)
        add(shortest(from, to, if (mode == Mode.MIN_TIME) Mode.MIN_TRANSFER else Mode.MIN_TIME))
        for (t in best.transferStations) add(shortest(from, to, mode, banned = setOf(t)))
        if (best.transferStations.size >= 2) add(shortest(from, to, mode, banned = best.transferStations.toSet()))
        val limit = best.totalSec + 30 * 60
        return found.values.filter { it.totalSec <= limit }.sortedBy { it.cost(mode) }.take(n)
    }

    /** route의 idx번째 환승(legs[idx] -> legs[idx+1])을 다른 역에서 하는 후보들 */
    fun transferOptions(route: Route, idx: Int, mode: Mode): List<TransferOption> {
        val a = route.legs[idx].line
        val b = route.legs[idx + 1].line
        val cur = route.legs[idx].to
        if (a == b) return emptyList() // 같은 노선 갈림길 환승은 바꿀 수 없음
        val out = ArrayList<TransferOption>()
        for ((st, ls) in net.linesAt) {
            if (st == cur || a !in ls || b !in ls) continue
            if (st == route.origin || st == route.dest) continue
            val r1 = shortest(route.origin, st, mode, endLine = a) ?: continue
            val r2 = shortest(st, route.dest, mode, startLine = b) ?: continue
            out.add(TransferOption(st, Route(r1.legs + r2.legs, r1.transferSecs + net.transferAt(st) + r2.transferSecs)))
        }
        return out.sortedBy { it.route.cost(mode) }.take(12)
    }
}
