package com.hayoon.subwayalert.route

import org.json.JSONObject

data class Line(val id: String, val name: String, val color: Long)
data class Station(val id: Int, val name: String, val lat: Double, val lon: Double)
data class Hop(val to: Int, val line: String, val sec: Int)

/** 수도권 노선망. 순수 Kotlin(org.json만 사용)이라 JVM 단위테스트 가능. */
class Network(
    val version: Long,
    val generated: String,
    val lines: Map<String, Line>,
    val stations: Map<Int, Station>,
    val adj: Map<Int, List<Hop>>,
    val transferSec: Int,
    val transferOverride: Map<Int, Int>,
    private val noThrough: Set<String> = emptySet(),
) {
    /** 갈림길에서 prev -> junction -> next 로 같은 노선 직통이 가능한지 */
    /** 직통 제한이 있는 갈림길 (같은 노선끼리 갈아타기 허용) */
    val branchJunctions: Set<String> = noThrough.map { it.split("|").let { p -> "${p[0]}|${p[2]}" } }.toSet()

    fun canPass(line: String, prev: Int, junction: Int, next: Int): Boolean =
        prev < 0 || "$line|$prev|$junction|$next" !in noThrough

    /** 역별 지나는 노선 */
    val linesAt: Map<Int, Set<String>> = adj.mapValues { (_, hops) -> hops.map { it.line }.toSet() }

    fun transferAt(station: Int): Int = transferOverride[station] ?: transferSec

    fun lineName(id: String): String = shortName(id)

    /** 같은 노선 위에서 다음으로 갈 수 있는 역들 */
    fun neighbors(station: Int, line: String): List<Hop> =
        adj[station].orEmpty().filter { it.line == line }

    fun hopSec(a: Int, b: Int, line: String): Int =
        adj[a].orEmpty().firstOrNull { it.to == b && it.line == line }?.sec ?: 120

    /** 이름으로 역 찾기 (동명이역은 여러 개) */
    fun search(q: String): List<Station> {
        val k = q.trim().removeSuffix("역")
        if (k.isEmpty()) return emptyList()
        return stations.values
            .filter { it.name.contains(k) }
            .sortedWith(compareBy({ !it.name.startsWith(k) }, { it.name.length }, { it.name }))
            .take(30)
    }

    /** s0 -> s1 방향으로 노선 끝까지 따라가 종점 이름 (갈림길이면 null) */
    fun terminalToward(s0: Int, s1: Int, line: String): String? {
        var prev = s0; var cur = s1
        repeat(200) {
            val next = neighbors(cur, line).map { it.to }.filter { it != prev }.distinct()
            when (next.size) {
                0 -> return stations[cur]?.name
                1 -> { prev = cur; cur = next[0] }
                else -> return null
            }
        }
        return null
    }

    companion object {
        fun shortName(id: String): String = when {
            id.all { it.isDigit() } -> "${id}호선"
            id == "I2" -> "인천2호선"
            id == "인천1" -> "인천1호선"
            id == "U" -> "의정부경전철"
            id == "W" -> "우이신설선"
            id == "Silim" -> "신림선"
            id == "용인" -> "에버라인"
            id == "김포 골드라인" -> "김포골드"
            id == "GTX-A" -> "GTX-A"
            id == "공항철도" -> "공항철도"
            id.endsWith("선") -> id
            else -> "${id}선"
        }

        fun parse(json: String): Network {
            val o = JSONObject(json)
            val lines = mutableMapOf<String, Line>()
            val la = o.getJSONArray("lines")
            for (k in 0 until la.length()) {
                val l = la.getJSONObject(k)
                val c = l.optString("color", "#888888").removePrefix("#")
                lines[l.getString("id")] = Line(l.getString("id"), l.getString("name"), 0xFF000000 or c.toLong(16))
            }
            val st = mutableMapOf<Int, Station>()
            val sa = o.getJSONArray("stations")
            for (k in 0 until sa.length()) {
                val s = sa.getJSONObject(k)
                st[s.getInt("i")] = Station(s.getInt("i"), s.getString("n"), s.getDouble("lat"), s.getDouble("lon"))
            }
            val adj = mutableMapOf<Int, MutableList<Hop>>()
            val ea = o.getJSONArray("edges")
            for (k in 0 until ea.length()) {
                val e = ea.getJSONObject(k)
                val a = e.getInt("a"); val b = e.getInt("b"); val l = e.getString("l"); val s = e.getInt("s")
                if (a !in st || b !in st) continue
                adj.getOrPut(a) { mutableListOf() }.add(Hop(b, l, s))
                adj.getOrPut(b) { mutableListOf() }.add(Hop(a, l, s))
            }
            val tro = mutableMapOf<Int, Int>()
            o.optJSONObject("transfers")?.let { t -> t.keys().forEach { key -> tro[key.toInt()] = t.getInt(key) } }
            val nt = mutableSetOf<String>()
            o.optJSONArray("noThrough")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val r = arr.getJSONArray(k)
                    val l = r.getString(0); val a = r.getInt(1); val j = r.getInt(2); val b = r.getInt(3)
                    nt.add("$l|$a|$j|$b"); nt.add("$l|$b|$j|$a")
                }
            }
            return Network(
                o.getLong("version"), o.optString("generated"), lines, st, adj,
                o.optInt("transferSec", 360), tro, nt,
            )
        }
    }
}
