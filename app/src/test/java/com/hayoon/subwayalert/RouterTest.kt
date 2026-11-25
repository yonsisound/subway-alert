package com.hayoon.subwayalert

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

    @Test fun tracker() {
        val r = router.shortest(id("강남"), id("홍대입구"), Mode.MIN_TIME)!!
        val t = RideTracker(net, r)
        val fired = HashSet<String>()
        var e = 0
        while (e < r.totalSec + 300) {
            t.dueAlerts(e, fired).forEach { fired.add(it.key); println("${e / 60}:${"%02d".format(e % 60)} ${it.title}") }
            e += 5
        }
        assertEquals(t.targets.size * 2, fired.size)
        assertTrue(t.status(r.totalSec + 10).finished)
    }
}
