package com.hayoon.subwayalert.ride

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 진동 + 알림음 + (선택) 음성 안내 */
class Announcer(private val ctx: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ready = false

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            ready = t.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
            t.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
    }

    fun announce(a: Alert) {
        vibrate()
        val voice = ctx.getSharedPreferences("app", 0).getBoolean("voice", true)
        if (voice && ready) {
            // 알림음 먼저, 이어서 음성
            playChime()
            tts?.speak(a.speech, TextToSpeech.QUEUE_ADD, null, a.key)
        } else playChime()
    }

    private fun playChime() {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return  // 무음/진동 모드면 알림음 생략
            val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
            r.play()
        }
    }

    private fun vibrate() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createWaveform(RideService.PATTERN, -1))
    }

    fun shutdown() { tts?.shutdown(); tts = null }
}
