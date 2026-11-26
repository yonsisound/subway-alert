package com.hayoon.subwayalert.ride

import kotlin.math.sqrt

/**
 * 가속도(+자이로) 센서로 열차 출발/정차를 감지 (순수 로직, 1초 창 단위).
 *
 * v1.2: 안드로이드 중력 센서는 지속 가속을 몇 초 만에 '기울기'로 흡수해 버려서
 * 중력을 직접 느리게(약 15초) 추정한다. 휴대폰을 돌리면(자이로/자세 변화) 중력 추정을 즉시 다시 맞추고
 * 잠깐 판단을 쉰다.
 */
class MotionDetector(private val listener: Listener, private val cfg: Config = Config()) {

    data class Config(
        val accelMin: Double = 0.25,       // m/s², 가감속으로 볼 1초 평균 수평가속도
        val accelSecs: Int = 3,            // 이만큼 연속되면 가감속 구간
        val stillMh: Double = 0.12,
        val stillVib: Double = 0.15,       // 정지로 볼 진동(크기 표준편차)
        val stillSecs: Int = 3,            // 감속 후 이만큼 조용하면 정차
        val longStillSecs: Int = 15,       // 감속 감지를 놓쳤어도 이만큼 조용하면 정차 후보(신뢰도 낮음)
        val walkVib: Double = 1.3,
        val walkSteps: Int = 5,            // 최근 8초 걸음 수
        val gravityTauSec: Double = 15.0,      // 자이로 없을 때
        val gravityTauGyroSec: Double = 8.0,   // 자이로로 회전을 따라갈 때
        val rotateRad: Double = 0.35,      // rad/s 이상이면 휴대폰을 돌리는 중
        val tiltResetDeg: Double = 12.0,   // 1초 평균 방향이 중력 추정과 이만큼 다르면 자세 변경
    )

    interface Listener {
        fun onWindow(w: Window) {}
        fun onDeparture(tMs: Long)
        fun onStop(tMs: Long, longStill: Boolean)
    }

    data class Window(val tMs: Long, val mh: Double, val vib: Double, val steps: Int, val walking: Boolean,
                      val accelRun: Int, val stillRun: Int, val moving: Boolean, val rotating: Boolean)

    // 중력 추정
    private val g = DoubleArray(3)
    private var gInit = false
    private var lastT = -1L
    private var rotateUntil = 0L
    private val w = DoubleArray(3)
    private var hasGyro = false

    // 창 누적
    private var winStart = -1L
    private var n = 0
    private var ax0 = 0.0; private var ay0 = 0.0; private var az0 = 0.0
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0
    private var sm = 0.0; private var smm = 0.0
    private var rotSeen = false
    private val steps = ArrayDeque<Long>()

    // 상태
    var moving = false; private set
    private var accelRun = 0
    private var stillRun = 0
    private var brakedAt = -1L
    private var lastDir: DoubleArray? = null
    private var accelFiredThisRun = false
    private var departRun = false

    fun reset(moving: Boolean) {
        this.moving = moving; accelRun = 0; stillRun = 0; brakedAt = -1; lastDir = null
        accelFiredThisRun = false; departRun = false
    }

    fun step(tMs: Long) { steps.addLast(tMs) }

    fun gyro(tMs: Long, wx: Float, wy: Float, wz: Float) {
        hasGyro = true; w[0] = wx.toDouble(); w[1] = wy.toDouble(); w[2] = wz.toDouble()
        val mag = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        if (mag > cfg.rotateRad) { rotateUntil = tMs + 2000; rotSeen = true }
    }

    fun feed(tMs: Long, ax: Float, ay: Float, az: Float) {
        if (!gInit) { g[0] = ax.toDouble(); g[1] = ay.toDouble(); g[2] = az.toDouble(); gInit = true; lastT = tMs }
        val dt = ((tMs - lastT).coerceIn(1, 500)) / 1000.0; lastT = tMs
        if (hasGyro) { // 휴대폰 좌표계에서 고정된 중력 벡터의 변화: dg/dt = g × ω (천천히 기울어져도 따라감)
            val cx = g[1] * w[2] - g[2] * w[1]; val cy = g[2] * w[0] - g[0] * w[2]; val cz = g[0] * w[1] - g[1] * w[0]
            g[0] += cx * dt; g[1] += cy * dt; g[2] += cz * dt
        }
        val tau = if (hasGyro) cfg.gravityTauGyroSec else cfg.gravityTauSec
        val k = if (tMs < rotateUntil) 0.3 else dt / tau   // 크게 돌리는 중이면 빠르게 따라감
        g[0] += k * (ax - g[0]); g[1] += k * (ay - g[1]); g[2] += k * (az - g[2])

        if (winStart < 0) winStart = tMs
        val lx = ax - g[0]; val ly = ay - g[1]; val lz = az - g[2]
        val g2 = (g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(1e-3)
        val dot = (lx * g[0] + ly * g[1] + lz * g[2]) / g2
        sx += lx - dot * g[0]; sy += ly - dot * g[1]; sz += lz - dot * g[2]
        ax0 += ax; ay0 += ay; az0 += az
        val m = sqrt(lx * lx + ly * ly + lz * lz)
        sm += m; smm += m * m; n++
        if (tMs - winStart >= 1000 && n >= 5) closeWindow(tMs)
    }

    private fun closeWindow(tMs: Long) {
        val mx = sx / n; val my = sy / n; val mz = sz / n
        var mh = sqrt(mx * mx + my * my + mz * mz)
        val mean = sm / n
        val vib = sqrt((smm / n - mean * mean).coerceAtLeast(0.0))
        // 자세 변경: 1초 평균 가속도 방향 vs 중력 추정 (열차 가속 0.8m/s² 는 약 5°라 12°면 자세 변경)
        val rx = ax0 / n; val ry = ay0 / n; val rz = az0 / n
        val rn = sqrt(rx * rx + ry * ry + rz * rz); val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        val cos = if (rn > 1e-3 && gn > 1e-3) (rx * g[0] + ry * g[1] + rz * g[2]) / (rn * gn) else 1.0
        val tilted = cos < Math.cos(Math.toRadians(cfg.tiltResetDeg))
        val rotating = rotSeen || tilted || tMs < rotateUntil
        if (tilted) { g[0] = rx; g[1] = ry; g[2] = rz; rotateUntil = tMs + 2000 }
        if (rotating) mh = 0.0

        while (steps.isNotEmpty() && tMs - steps.first() > 8000) steps.removeFirst()
        val walking = steps.size >= cfg.walkSteps || vib > cfg.walkVib
        winStart = tMs; n = 0; sx = 0.0; sy = 0.0; sz = 0.0; sm = 0.0; smm = 0.0
        ax0 = 0.0; ay0 = 0.0; az0 = 0.0; rotSeen = false

        val dir = if (mh > 1e-6) doubleArrayOf(mx / mh, my / mh, mz / mh) else null
        val consistent = lastDir == null || dir == null ||
            (lastDir!![0] * dir[0] + lastDir!![1] * dir[1] + lastDir!![2] * dir[2]) > 0.5
        if (!walking && !rotating && mh >= cfg.accelMin && consistent) { accelRun++; lastDir = dir }
        else if (rotating) { /* 돌리는 동안은 판단 보류 */ }
        else {
            if (accelRun >= cfg.accelSecs && moving && !departRun) brakedAt = tMs
            if (accelRun > 0) departRun = false
            accelRun = 0; lastDir = null; accelFiredThisRun = false
        }
        val still = !walking && !rotating && mh < cfg.stillMh && vib < cfg.stillVib
        stillRun = if (still) stillRun + 1 else if (rotating) stillRun else 0

        if (accelRun >= cfg.accelSecs && !accelFiredThisRun) {
            accelFiredThisRun = true
            if (!moving) { moving = true; brakedAt = -1; departRun = true; listener.onDeparture(tMs) }
        }
        if (moving) {
            val braked = brakedAt > 0 && tMs - brakedAt < 40_000
            if (braked && stillRun >= cfg.stillSecs) { moving = false; brakedAt = -1; listener.onStop(tMs, false) }
            else if (stillRun >= cfg.longStillSecs) { moving = false; brakedAt = -1; listener.onStop(tMs, true) }
        }
        listener.onWindow(Window(tMs, mh, vib, steps.size, walking, accelRun, stillRun, moving, rotating))
    }
}
