package com.hayoon.subwayalert

import com.hayoon.subwayalert.ride.Phase
import com.hayoon.subwayalert.ride.RideTracker
import com.hayoon.subwayalert.route.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RouterTest {
    private val net = Network.parse(File("src/main/assets/subway_data.json").readText())
    private val router = Router(net)
    private fun id(n: String) = net.stations.values.first { it.name == n }.id
    private fun show(r: Route) = "${r.totalSec / 60}분 환승${r.transfers} " + r.legs.joinToString(" > ") {
        "${Network.shortName(it.line)}(${net.stations[it.from]!!.name}→${net.stations[it.to]!!.name},${it.stops})"
    }

    @Test fun routes() {
        for ((a, b) in listOf("강남" to "홍대입구", "수원" to "잠실", "인천공항1터미널" to "강남", "도봉산" to "판교", "신림" to "여의도")) {
            for (m in Mode.values()) {
                val rs = router.alternatives(id(a), id(b), m)
                assertTrue("$a→$b", rs.isNotEmpty())
                println("[$m] $a→$b"); rs.forEach { println("   " + show(it)) }
            }
        }
    }

    @Test fun sameLineNoTransfer() {
        val r = router.shortest(id("강남"), id("시청"), Mode.MIN_TRANSFER)!!
        assertEquals(0, r.transfers)
        assertEquals("2", r.legs[0].line)
    }

    @Test fun transferOptions() {
        val r = router.shortest(id("잠실"), id("서울역"), Mode.MIN_TRANSFER)!!
        println("base " + show(r))
        if (r.transfers > 0) router.transferOptions(r, 0, Mode.MIN_TRANSFER).forEach {
            println("  via ${net.stations[it.station]!!.name}: " + show(it.route))
            assertEquals(r.legs[0].line, it.route.legs.first { l -> l.to == it.station }.line)
        }
    }

    @Test fun branches() {
        // 성수지선 용답 -> 건대입구 : 성수에서 2호선끼리 환승해야 함
        val r = router.shortest(id("용답"), id("건대입구"), Mode.MIN_TRANSFER)!!
        println("branch " + show(r)); assertEquals(1, r.transfers)
        // 구일 -> 가산디지털단지 : 구로에서 갈아타기
        val r2 = router.shortest(id("구일"), id("가산디지털단지"), Mode.MIN_TRANSFER)!!
        println("branch " + show(r2)); assertEquals(1, r2.transfers)
        val r3 = router.shortest(id("구일"), id("시청"), Mode.MIN_TRANSFER)!!
        println("through " + show(r3)); assertEquals(0, r3.transfers)
    }

    @Test fun tracker() { trackerOn("강남", "홍대입구"); trackerOn("잠실", "서울역"); trackerOn("수원", "잠실") }

    private fun trackerOn(a: String, b: String) {
        val r = router.shortest(id(a), id(b), Mode.MIN_TIME)!!
        println("tracker " + show(r))
        val t = RideTracker(net, r)
        var now = 1_000_000L
        t.start(now)
        val log = ArrayList<String>()
        fun run(a: List<com.hayoon.subwayalert.ride.Alert>) = a.forEach { log += it.title; println("  ${(now - 1_000_000) / 1000}s ${it.title} / ${it.speech}") }
        // 승차 대기 5분: 아무 일도 없어야 함
        now += 300_000; run(t.tick(now)); assertEquals(Phase.WAITING, t.phase)
        // 매 구간: 출발 → 정차 (신호대기 같은 이른 정차 하나 섞기)
        while (t.phase != Phase.FINISHED) {
            if (t.phase == Phase.AT_STATION && t.stops[t.idx].kind == com.hayoon.subwayalert.ride.Stop.Kind.TRANSFER) now += 240_000
            run(t.onDeparture(now))
            val hop = t.stops[t.idx + 1].hopSec * 1000L
            now += 20_000; run(t.onStop(now))           // 너무 이른 정차 → 무시
            assertEquals(Phase.MOVING, t.phase)
            now += hop - 20_000; run(t.onStop(now))
            now += 30_000
        }
        assertEquals(t.targets.size * 2, log.size)
    }

    @Test fun trackerFallback() {
        val r = router.shortest(id("강남"), id("시청"), Mode.MIN_TRANSFER)!!
        val t = RideTracker(net, r); var now = 0L; t.start(now)
        t.onDeparture(now)
        // 센서가 전혀 감지 못해도 시간 예비로 끝까지 진행
        var alerts = 0
        repeat(2000) { now += 5000; alerts += t.tick(now).size }
        assertEquals(Phase.FINISHED, t.phase); assertEquals(2, alerts)
    }

    private class Rec : com.hayoon.subwayalert.ride.MotionDetector.Listener {
        val ev = ArrayList<String>()
        override fun onDeparture(tMs: Long) { ev += "D${tMs / 1000}" }
        override fun onStop(tMs: Long, longStill: Boolean) { ev += (if (longStill) "L" else "S") + "${tMs / 1000}" }
    }

    @Test fun motionDetector() {
        // 정지 20s → 가속 12s(0.6m/s²) → 주행 진동 60s → 감속 10s → 정지 20s, 휴대폰은 비스듬히(중력이 y,z에 분산)
        val rec = Rec(); val d = com.hayoon.subwayalert.ride.MotionDetector(rec)
        val rnd = java.util.Random(1); var tt = 0L
        fun seg(secs: Int, a: Double, noise: Double) { repeat(secs * 20) { tt += 50
            d.feed(tt, (a + rnd.nextGaussian() * noise).toFloat(), (6.94 + rnd.nextGaussian() * noise).toFloat(), (6.94 + rnd.nextGaussian() * noise).toFloat()) } }
        seg(20, 0.0, 0.03); seg(12, 0.6, 0.12); seg(60, 0.0, 0.3); seg(10, -0.6, 0.12); seg(20, 0.0, 0.03)
        println("motion ${rec.ev}")
        assertEquals(listOf("D", "S"), rec.ev.map { it.take(1) })
    }

    @Test fun motionRotationNoFalseDeparture() {
        // 정지 상태에서 휴대폰을 뒤집음(중력 방향 변화 + 자이로) → 출발로 착각하면 안 됨
        val rec = Rec(); val d = com.hayoon.subwayalert.ride.MotionDetector(rec)
        var tt = 0L
        repeat(400) { tt += 50; d.feed(tt, 0f, 0f, 9.81f) }
        repeat(40) { k -> tt += 50; val ang = Math.PI / 2 * (k + 1) / 40
            d.gyro(tt, 1.5f, 0f, 0f); d.feed(tt, 0f, (9.81 * Math.sin(ang)).toFloat(), (9.81 * Math.cos(ang)).toFloat()) }
        repeat(600) { tt += 50; d.feed(tt, 0f, 9.81f, 0f) }
        println("rotation ${rec.ev}")
        assertTrue(rec.ev.isEmpty())
    }

    @Test fun motionSlowTiltNoFalseDeparture() {
        // 조는 동안 휴대폰이 10초에 걸쳐 15° 천천히 기울어짐 (자이로 0.026rad/s, 회전 판정 기준 아래)
        val rec = Rec(); val d = com.hayoon.subwayalert.ride.MotionDetector(rec)
        var tt = 0L
        repeat(400) { tt += 50; d.gyro(tt, 0f, 0f, 0f); d.feed(tt, 0f, 0f, 9.81f) }
        val total = Math.toRadians(15.0); val rate = total / 10.0
        repeat(200) { k -> tt += 50; val ang = total * (k + 1) / 200
            d.gyro(tt, rate.toFloat(), 0f, 0f); d.feed(tt, 0f, (9.81 * Math.sin(ang)).toFloat(), (9.81 * Math.cos(ang)).toFloat()) }
        repeat(600) { tt += 50; d.gyro(tt, 0f, 0f, 0f); d.feed(tt, 0f, (9.81 * Math.sin(total)).toFloat(), (9.81 * Math.cos(total)).toFloat()) }
        println("slowtilt ${rec.ev}")
        assertTrue(rec.ev.isEmpty())
    }

    @Test fun motionWithGyroStillDetects() {
        val rec = Rec(); val d = com.hayoon.subwayalert.ride.MotionDetector(rec)
        val rnd = java.util.Random(2); var tt = 0L
        fun seg(secs: Int, a: Double, noise: Double) { repeat(secs * 20) { tt += 50
            d.gyro(tt, (rnd.nextGaussian() * 0.01).toFloat(), (rnd.nextGaussian() * 0.01).toFloat(), (rnd.nextGaussian() * 0.01).toFloat())
            d.feed(tt, (a + rnd.nextGaussian() * noise).toFloat(), (rnd.nextGaussian() * noise).toFloat(), (9.81 + rnd.nextGaussian() * noise).toFloat()) } }
        seg(20, 0.0, 0.03); seg(12, 0.6, 0.12); seg(60, 0.0, 0.3); seg(10, -0.6, 0.12); seg(20, 0.0, 0.03)
        println("gyro-motion ${rec.ev}")
        assertEquals(listOf("D", "S"), rec.ev.map { it.take(1) })
    }
}
