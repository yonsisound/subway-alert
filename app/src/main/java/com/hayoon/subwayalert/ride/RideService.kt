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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
data class RideUi(val tracker: RideTracker, val status: RideStatus, val elapsed: Int)

object RideState {
    val ui = MutableStateFlow<RideUi?>(null)
    val flow: StateFlow<RideUi?> get() = ui
}

class RideService : Service(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private var tracker: RideTracker? = null
    private var startMs = 0L
    private var shift = 0
    private val fired = HashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("ride", 0) }

    private val tick = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 5000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_ROUTE) ?: return START_NOT_STICKY
                startMs = System.currentTimeMillis(); shift = 0; fired.clear()
                prefs.edit().putString("route", json).putLong("start", startMs).putInt("shift", 0)
                    .putString("fired", "").apply()
                begin(DataRepository.routeFromJson(json))
            }
            ACTION_NEXT -> adjustToStop { t, cur -> minOf(cur + 1, t.stops.size - 1) }
            ACTION_BOARDED -> boarded()
            ACTION_SET_STOP -> {
                val idx = intent.getIntExtra(EXTRA_STOP, -1)
                if (idx >= 0) adjustToStop { _, _ -> idx }
            }
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
            else -> { // 시스템 재시작: 저장된 상태 복원
                val json = prefs.getString("route", null)
                if (json == null || tracker != null) { if (tracker == null) stopSelf(); return START_NOT_STICKY }
                startMs = prefs.getLong("start", System.currentTimeMillis())
                shift = prefs.getInt("shift", 0)
                fired.addAll(prefs.getString("fired", "")!!.split(",").filter { it.isNotEmpty() })
                begin(DataRepository.routeFromJson(json))
            }
        }
        return START_STICKY
    }

    private fun begin(route: Route) {
        val net = DataRepository.network(this)
        val t = RideTracker(net, route)
        tracker = t
        val n = ongoing(t, t.status(0))
        when {
            hasLocation() -> ServiceCompat.startForeground(this, ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            Build.VERSION.SDK_INT >= 34 -> ServiceCompat.startForeground(this, ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else -> startForeground(ONGOING_ID, n)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubwayAlert:ride").apply {
            acquire((route.totalSec + 3600) * 1000L)
        }
        startLocation()
        handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun elapsed(): Int = ((System.currentTimeMillis() - startMs) / 1000).toInt() + shift

    private fun setElapsed(target: Int) {
        shift = target - ((System.currentTimeMillis() - startMs) / 1000).toInt()
        prefs.edit().putInt("shift", shift).apply()
    }

    private fun adjustToStop(pick: (RideTracker, Int) -> Int) {
        val t = tracker ?: return
        val cur = t.status(elapsed()).current
        val idx = pick(t, cur)
        // 뒤로 보정하면 그 이후 알림은 다시 울릴 수 있게
        if (idx < cur) { fired.removeAll { k -> k.drop(3).toIntOrNull()?.let { it > idx } == true }; saveFired() }
        setElapsed(t.elapsedForArrival(idx))
        update()
    }

    private fun boarded() {
        val t = tracker ?: return
        val st = t.status(elapsed())
        if (t.stops[st.current].kind == Stop.Kind.TRANSFER) setElapsed(t.elapsedForDeparture(st.current))
        update()
    }

    private fun update() {
        val t = tracker ?: return
        val e = elapsed()
        val st = t.status(e)
        for (a in t.dueAlerts(e, fired)) { fired.add(a.key); alert(a) }
        saveFired()
        RideState.ui.value = RideUi(t, st, e)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ONGOING_ID, ongoing(t, st))
        if (st.finished && e > t.stops.last().arriveAt + 120) finish()
    }

    private fun saveFired() = prefs.edit().putString("fired", fired.joinToString(",")).apply()

    private fun ongoing(t: RideTracker, st: RideStatus): Notification {
        val net = t.net
        val cur = net.stations[t.stops[st.current].station]?.name ?: ""
        val title: String
        val text: String
        if (st.finished) {
            title = "도착: $cur"; text = "안내를 마칩니다."
        } else {
            val tg = st.nextTarget?.let { t.stops[it] }
            val tgName = tg?.let { net.stations[it.station]?.name } ?: ""
            val what = if (tg?.kind == Stop.Kind.DEST) "하차" else "환승"
            title = if (st.waitingTransfer) "$cur 환승 중" else "${if (st.atStation) "현재" else "지남"}: $cur"
            text = "$tgName $what · ${st.stopsToTarget}정거장 · 약 ${(st.secToTarget + 59) / 60}분"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        if (st.waitingTransfer) b.addAction(0, "환승 열차 탑승", action(ACTION_BOARDED, 2))
        else if (!st.finished) b.addAction(0, "다음 역 도착", action(ACTION_NEXT, 1))
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
            .setVibrate(PATTERN).setAutoCancel(true).setContentIntent(open)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID + Math.floorMod(a.key.hashCode(), 1000), n)
        vibrate()
    }

    private fun vibrate() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
    }

    // ---- 위치 보정 ----
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
        if (System.currentTimeMillis() - loc.time > 60_000) return
        val cur = t.status(elapsed()).current
        val k = t.snap(loc.latitude, loc.longitude, loc.accuracy, cur) ?: return
        // 추정이 늦을 때만 앞으로 당김 (지하에서 튀는 위치로 되돌리지 않음)
        if (k > cur) { setElapsed(t.elapsedForArrival(k)); update() }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun finish() {
        handler.removeCallbacks(tick)
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        prefs.edit().clear().apply()
        tracker = null
        RideState.ui.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"; const val ACTION_NEXT = "next"; const val ACTION_BOARDED = "boarded"
        const val ACTION_SET_STOP = "setStop"; const val ACTION_STOP = "stop"
        const val EXTRA_ROUTE = "route"; const val EXTRA_STOP = "stop"
        const val CH_ONGOING = "ongoing"; const val CH_ALERT = "alert2"
        const val ONGOING_ID = 1; const val ALERT_ID = 100
        val PATTERN = longArrayOf(0, 600, 250, 600, 250, 900)

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "탑승 중 안내", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "환승·하차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = PATTERN
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
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

