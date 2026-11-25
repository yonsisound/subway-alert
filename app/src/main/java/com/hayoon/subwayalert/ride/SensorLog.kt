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
    private var lines = 0

    init { w.write("time,mh,vib,steps,walking,accelRun,stillRun,moving,phase,idx,event\n") }

    @Synchronized fun window(x: MotionDetector.Window, phase: String, idx: Int) {
        w.write("${x.tMs},${"%.3f".format(x.mh)},${"%.3f".format(x.vib)},${x.steps},${if (x.walking) 1 else 0}," +
            "${x.accelRun},${x.stillRun},${if (x.moving) 1 else 0},$phase,$idx,\n")
        if (++lines % 30 == 0) w.flush()
    }

    @Synchronized fun event(tMs: Long, e: String) { w.write("$tMs,,,,,,,,,,$e\n"); w.flush() }

    @Synchronized fun close() { runCatching { w.flush(); w.close() } }

    companion object {
        fun dir(ctx: Context) = File(ctx.filesDir, "logs").apply { mkdirs() }

        fun open(ctx: Context, from: String?, to: String?): SensorLog? = runCatching {
            val d = dir(ctx)
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }   // 최근 10개만
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())
            SensorLog(File(d, "ride_${stamp}_${from}_$to.csv".replace(Regex("[\\\\/:*?\"<>| ]"), "")))
        }.getOrNull()

        fun latest(ctx: Context): File? = dir(ctx).listFiles()?.filter { it.length() > 100 }?.maxByOrNull { it.lastModified() }
    }
}
