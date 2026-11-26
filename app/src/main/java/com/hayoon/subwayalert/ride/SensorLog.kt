package com.hayoon.subwayalert.ride

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 탑승 중 센서 판단값(1초 단위)과 이벤트 기록 → 기준값 조정용 */
class SensorLog private constructor(val file: File) {
    private val w: BufferedWriter = file.bufferedWriter()
    val rawFile = File(file.parentFile, file.name.replace("ride_", "raw_"))
    private val rw: BufferedWriter = rawFile.bufferedWriter()
    private var lines = 0
    private var rawLines = 0

    init {
        w.write("time,mh,vib,steps,walking,accelRun,stillRun,moving,phase,idx,event\n")
        rw.write("time,ax,ay,az,gx,gy,gz\n")
    }

    @Synchronized fun raw(tMs: Long, values: String) {
        rw.write("$tMs,$values\n"); if (++rawLines % 200 == 0) rw.flush()
    }

    @Synchronized fun window(x: MotionDetector.Window, phase: String, idx: Int) {
        w.write("${x.tMs},${"%.3f".format(x.mh)},${"%.3f".format(x.vib)},${x.steps},${if (x.walking) 1 else 0}," +
            "${x.accelRun},${x.stillRun},${if (x.moving) 1 else 0},$phase,$idx,${if (x.rotating) "rot" else ""}\n")
        if (++lines % 30 == 0) w.flush()
    }

    @Synchronized fun event(tMs: Long, e: String) { w.write("$tMs,,,,,,,,,,$e\n"); w.flush() }

    @Synchronized fun close() { runCatching { w.flush(); w.close() }; runCatching { rw.flush(); rw.close() } }

    companion object {
        fun dir(ctx: Context) = File(ctx.filesDir, "logs").apply { mkdirs() }

        fun open(ctx: Context, from: String?, to: String?): SensorLog? = runCatching {
            val d = dir(ctx)
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(18)?.forEach { it.delete() }   // 최근 10회분만
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())
            SensorLog(File(d, "ride_${stamp}_${from}_$to.csv".replace(Regex("[\\\\/:*?\"<>| ]"), "")))
        }.getOrNull()

        fun latest(ctx: Context): File? = dir(ctx).listFiles()?.filter { it.name.startsWith("ride_") && it.length() > 100 }?.maxByOrNull { it.lastModified() }
        fun rawOf(f: File): File? = File(f.parentFile, f.name.replace("ride_", "raw_")).takeIf { it.exists() }
    }
}
