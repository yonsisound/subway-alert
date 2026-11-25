package com.hayoon.subwayalert.ride

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hayoon.subwayalert.R
import com.hayoon.subwayalert.data.DataRepository
import com.hayoon.subwayalert.route.Route
import com.hayoon.subwayalert.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI에서 관찰하는 탑승 상태 */
data class RideUi(val tracker: RideTracker, val status: RideStatus, val now: Long, val sensorOk: Boolean)

object RideState {
    val ui = MutableStateFlow<RideUi?>(null)
    val flow: StateFlow<RideUi?> get() = ui
}

class RideService : Service(), LocationListener, SensorEventListener, MotionDetector.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var sensorThread: HandlerThread? = null
    private var tracker: RideTracker? = null
    private var detector: MotionDetector? = null
    private var announcer: Announcer? = null
    private var log: SensorLog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("ride", 0) }
    private val grav = FloatArray(3)
    private var hasGravitySensor = false
    private var gravInit = false
    private var sensorOk = false

    private val tick = object : Runnable {
        override fun run() { tracker?.let { apply(it.tick(now())) }; publish(); main.postDelayed(this, 5000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        announcer = Announcer(this)
    }

    private fun now() = System.currentTimeMillis()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_ROUTE) ?: return START_NOT_STICKY
                prefs.edit().clear().putString("route", json).apply()
                log?.close(); log = null
                begin(DataRepository.routeFromJson(json), restore = false)
            }
            ACTION_DEPART -> tracker?.let { apply(it.onDeparture(now(), manual = true)) }
            ACTION_SET_STOP -> {
                val idx = intent.getIntExtra(EXTRA_STOP, -1)
                tracker?.let { if (idx >= 0) apply(it.setStation(idx, now())) }
            }
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            else -> { // 시스템 재시작/앱 재실행: 저장된 상태 복원
                if (tracker != null) { publish(); return START_STICKY }
                val json = prefs.getString("route", null)
                if (json == null) { stopSelf(); return START_NOT_STICKY }
                begin(DataRepository.routeFromJson(json), restore = true)
            }
        }
        publish()
        return START_STICKY
    }

    private fun begin(route: Route, restore: Boolean) {
        val net = DataRepository.network(this)
        val t = RideTracker(net, route)
        if (restore) {
            t.restore(Phase.valueOf(prefs.getString("phase", "WAITING")!!), prefs.getInt("idx", 0),
                prefs.getLong("since", now()), prefs.getString("fired", "")!!.split(",").filter { it.isNotEmpty() })
        } else t.start(now())
        tracker = t
        save()
        val n = ongoing(t, t.status(now()))
        when {
            hasLocation() -> ServiceCompat.startForeground(this, ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            Build.VERSION.SDK_INT >= 34 -> ServiceCompat.startForeground(this, ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else -> startForeground(ONGOING_ID, n)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubwayAlert:ride").apply {
            acquire((route.totalSec + 2 * 3600) * 1000L)   // 승차 대기 시간까지 넉넉히
        }
        if (log == null) log = SensorLog.open(this, net.stations[route.origin]?.name, net.stations[route.dest]?.name)
        startSensors()
        startLocation()
        main.removeCallbacks(tick); main.post(tick)
    }

    /** tracker 전이 결과 처리: 알림, 저장, 센서 상태 동기화 */
    private fun apply(alerts: List<Alert>) {
        val t = tracker ?: return
        for (a in alerts) { alert(a); log?.event(now(), "ALERT ${a.key}") }
        detector?.let { d -> if (d.moving != (t.phase == Phase.MOVING)) d.reset(t.phase == Phase.MOVING) }
        save()
        publish()
        if (t.phase == Phase.FINISHED && alerts.isNotEmpty())
            main.postDelayed({ if (tracker?.phase == Phase.FINISHED) finish() }, 120_000)
    }

    private fun save() {
        val t = tracker ?: return
        prefs.edit().putString("phase", t.phase.name).putInt("idx", t.idx).putLong("since", t.since)
            .putString("fired", t.fired.joinToString(",")).apply()
    }

    private fun publish() {
        val t = tracker ?: return
        val st = t.status(now())
        RideState.ui.value = RideUi(t, st, now(), sensorOk)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ONGOING_ID, ongoing(t, st))
    }

    // ---------------- 센서 ----------------

    private fun startSensors() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(this)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) { sensorOk = false; return }
        if (detector == null) detector = MotionDetector(this)
        detector!!.reset(tracker?.phase == Phase.MOVING)
        val th = sensorThread ?: HandlerThread("sensors").also { it.start(); sensorThread = it }
        val h = Handler(th.looper)
        sm.registerListener(this, acc, 50_000, h)   // 20Hz
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { hasGravitySensor = true; sm.registerListener(this, it, 50_000, h) }
        if (Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, h) }
        sensorOk = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val d = detector ?: return
        val t = now()
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]; gravInit = true }
            Sensor.TYPE_STEP_DETECTOR -> d.step(t)
            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasGravitySensor) { // 중력 추정: 느린 저역통과 (약 8초)
                    val a = if (gravInit) 0.006f else 1f
                    for (k in 0..2) grav[k] = grav[k] + a * (e.values[k] - grav[k])
                    gravInit = true
                }
                if (!gravInit) return
                d.feed(t, e.values[0], e.values[1], e.values[2], grav[0], grav[1], grav[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // MotionDetector.Listener (센서 스레드에서 호출) → 상태 변경은 메인에서
    override fun onWindow(w: MotionDetector.Window) {
        val t = tracker
        log?.window(w, t?.phase?.name ?: "-", t?.idx ?: -1)
    }

    override fun onDeparture(tMs: Long) {
        main.post { log?.event(tMs, "DEPART"); tracker?.let { apply(it.onDeparture(tMs)) } }
    }

    override fun onStop(tMs: Long, longStill: Boolean) {
        main.post { log?.event(tMs, if (longStill) "STOP_LONG" else "STOP"); tracker?.let { apply(it.onStop(tMs, longStill)) } }
    }

    // ---------------- 알림 ----------------

    private fun ongoing(t: RideTracker, st: RideStatus): Notification {
        val net = t.net
        val cur = net.stations[t.stops[st.current].station]?.name ?: ""
        val tg = st.nextTarget?.let { t.stops[it] }
        val tgName = tg?.let { net.stations[it.station]?.name } ?: ""
        val what = if (tg?.kind == Stop.Kind.DEST) "하차" else "환승"
        val (title, text) = when (st.phase) {
            Phase.WAITING -> "$cur 승차 대기 중" to "열차가 출발하면 자동으로 안내를 시작해요"
            Phase.FINISHED -> "도착: $cur" to "안내를 마칩니다."
            Phase.AT_STATION -> (if (st.atTransfer) "$cur 환승 중" else "정차: $cur") to
                (if (st.atTransfer) "다음 열차가 출발하면 자동으로 이어서 안내해요" else "$tgName $what · ${st.stopsToTarget}정거장")
            Phase.MOVING -> "$cur → ${net.stations[t.stops[st.current + 1].station]?.name}" to
                "$tgName $what · ${st.stopsToTarget}정거장 · 약 ${(st.secToTarget + 59) / 60}분"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        if (st.waiting) b.addAction(0, "출발했어요", action(ACTION_DEPART, 1))
        b.addAction(0, "안내 종료", action(ACTION_STOP, 3))
        return b.build()
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, RideService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)

    private fun alert(a: Alert) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(a.title).setContentText(a.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true)   // 소리·진동은 Announcer가 직접
            .setAutoCancel(true).setContentIntent(open)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID + Math.floorMod(a.key.hashCode(), 1000), n)
        announcer?.announce(a)
    }

    // ---------------- 위치 보정 ----------------
    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocation()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(this)
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                runCatching { lm.requestLocationUpdates(p, 10_000L, 50f, this, Looper.getMainLooper()) }
            }
        }
    }

    override fun onLocationChanged(loc: Location) {
        val t = tracker ?: return
        if (now() - loc.time > 60_000) return
        val k = t.snap(loc.latitude, loc.longitude, loc.accuracy) ?: return
        if (k > t.idx) { log?.event(now(), "SNAP $k acc=${loc.accuracy}"); apply(t.onSnap(k, now())) }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun finish() {
        main.removeCallbacksAndMessages(null)
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        sensorThread?.quitSafely(); sensorThread = null
        log?.close(); log = null
        wakeLock?.let { if (it.isHeld) it.release() }
        prefs.edit().clear().apply()
        tracker = null
        RideState.ui.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        sensorThread?.quitSafely()
        log?.close()
        announcer?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"; const val ACTION_DEPART = "depart"
        const val ACTION_SET_STOP = "setStop"; const val ACTION_STOP = "stop"
        const val EXTRA_ROUTE = "route"; const val EXTRA_STOP = "stop"
        const val CH_ONGOING = "ongoing"; const val CH_ALERT = "alert3"
        const val ONGOING_ID = 1; const val ALERT_ID = 100
        val PATTERN = longArrayOf(0, 600, 250, 600, 250, 900)

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("alert2")
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "탑승 중 안내", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "환승·하차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false)
            })
        }

        fun start(ctx: Context, route: Route) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_ROUTE, DataRepository.routeToJson(route)))
        }

        fun send(ctx: Context, action: String, stop: Int = -1) {
            ctx.startService(Intent(ctx, RideService::class.java).setAction(action).putExtra(EXTRA_STOP, stop))
        }

        fun isRiding(ctx: Context) = ctx.getSharedPreferences("ride", 0).contains("route")
    }
}
