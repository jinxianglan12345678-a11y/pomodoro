package com.hanvon.clear6.pomodoro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object MinimalistSoundEngine {

    enum class SoundPreset(val displayName: String) {
        CRISP_TICK("清脆嘀嗒"),
        WOOD_BLOCK("木鱼短叩"),
        QUARTZ_BEEP("石英短鸣"),
        SOFT_CHIME("五度微铃"),
        MUTE("静音模式")
    }

    enum class AlertMode(val displayName: String) {
        SOUND_PRIMARY("优先音效（无扬声器降级闪烁）"),
        SOUND_ONLY("仅通知 + 极简音效（不闪屏）"),
        SOUND_AND_FLASH("极简音效 + 屏幕闪烁双重提醒"),
        FLASH_ONLY("仅通知 + 全屏反色闪烁")
    }

    private const val SAMPLE_RATE = 44100

    fun playPreset(preset: SoundPreset, isWorkCompleted: Boolean = true) {
        if (preset == SoundPreset.MUTE) return
        thread(start = true, isDaemon = true) {
            try {
                val pcmData = when (preset) {
                    SoundPreset.CRISP_TICK -> synthCrispDoubleTick(isWorkCompleted)
                    SoundPreset.WOOD_BLOCK -> synthWoodBlockTap(isWorkCompleted)
                    SoundPreset.QUARTZ_BEEP -> synthQuartzDoubleBeep(isWorkCompleted)
                    SoundPreset.SOFT_CHIME -> synthSoftFifthChime(isWorkCompleted)
                    SoundPreset.MUTE -> ShortArray(0)
                }
                if (pcmData.isNotEmpty()) {
                    playPcmBuffer(pcmData)
                }
            } catch (_: Exception) {}
        }
    }

    fun playSubtleMinuteTick() {
        thread(start = true, isDaemon = true) {
            try {
                val pcm = synthImpulseTone(freqStart = 1100.0, freqEnd = 550.0, durationMs = 22, amplitude = 0.25)
                playPcmBuffer(pcm)
            } catch (_: Exception) {}
        }
    }

    private fun synthCrispDoubleTick(isWorkCompleted: Boolean): ShortArray {
        val f1 = if (isWorkCompleted) 1480.0 else 1180.0
        val f2 = if (isWorkCompleted) 1180.0 else 1680.0
        val tick1 = synthImpulseTone(f1, f1 * 0.45, durationMs = 32, amplitude = 0.65)
        val gap = ShortArray((SAMPLE_RATE * 0.035).toInt())
        val tick2 = synthImpulseTone(f2, f2 * 0.45, durationMs = 32, amplitude = 0.65)
        return tick1 + gap + tick2
    }

    private fun synthWoodBlockTap(isWorkCompleted: Boolean): ShortArray {
        val f1 = 680.0
        val f2 = if (isWorkCompleted) 580.0 else 820.0
        val tap1 = synthWoodResonance(f1, durationMs = 70, amplitude = 0.7)
        val gap = ShortArray((SAMPLE_RATE * 0.03).toInt())
        val tap2 = synthWoodResonance(f2, durationMs = 75, amplitude = 0.65)
        return tap1 + gap + tap2
    }

    private fun synthQuartzDoubleBeep(isWorkCompleted: Boolean): ShortArray {
        val freq = if (isWorkCompleted) 2200.0 else 2600.0
        val pip1 = synthSquarePip(freq, durationMs = 50, amplitude = 0.35)
        val gap = ShortArray((SAMPLE_RATE * 0.04).toInt())
        val pip2 = synthSquarePip(freq, durationMs = 60, amplitude = 0.35)
        return pip1 + gap + pip2
    }

    private fun synthSoftFifthChime(isWorkCompleted: Boolean): ShortArray {
        val f1 = if (isWorkCompleted) 792.0 else 528.0
        val f2 = if (isWorkCompleted) 528.0 else 792.0
        val note1 = synthImpulseTone(f1, f1, durationMs = 110, amplitude = 0.55)
        val note2 = synthImpulseTone(f2, f2, durationMs = 150, amplitude = 0.55)
        return note1 + note2
    }

    private fun synthImpulseTone(
        freqStart: Double,
        freqEnd: Double,
        durationMs: Int,
        amplitude: Double
    ): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(numSamples)
        var phase = 0.0
        for (i in 0 until numSamples) {
            val progress = i.toDouble() / numSamples
            val freq = freqStart + (freqEnd - freqStart) * progress
            val env = exp(-6.5 * progress)
            phase += (2.0 * PI * freq) / SAMPLE_RATE
            val sample = sin(phase) * env * amplitude
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun synthWoodResonance(baseFreq: Double, durationMs: Int, amplitude: Double): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(numSamples)
        var phase1 = 0.0
        var phase2 = 0.0
        for (i in 0 until numSamples) {
            val progress = i.toDouble() / numSamples
            val env = exp(-8.0 * progress)
            phase1 += (2.0 * PI * baseFreq) / SAMPLE_RATE
            phase2 += (2.0 * PI * baseFreq * 1.52) / SAMPLE_RATE
            val raw = (0.75 * sin(phase1) + 0.25 * sin(phase2)) * env * amplitude
            out[i] = (raw * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun synthSquarePip(freq: Double, durationMs: Int, amplitude: Double): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(numSamples)
        var phase = 0.0
        for (i in 0 until numSamples) {
            phase += (2.0 * PI * freq) / SAMPLE_RATE
            val sq = if (sin(phase) >= 0.0) 1.0 else -1.0
            val sample = sq * amplitude
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun playPcmBuffer(pcmData: ShortArray) {
        val byteCount = pcmData.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(byteCount.coerceAtLeast(AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmData, 0, pcmData.size)
        track.play()
        val sleepMs = (pcmData.size * 1000L) / SAMPLE_RATE + 80L
        Thread.sleep(sleepMs)
        track.release()
    }

    // 针对汉王 Clear 6 禁用悬浮窗后的「无声听书级音频保活通道」：
    // 采用 MODE_STATIC 硬件缓冲区无限循环播放全 0 数字静音，0% CPU 占用、绝对无声，
    // 让汉王系统将本应用识别为「正在后台听书/放音乐」，切到《微信读书》永远不会被系统杀后台！
    private var keepAliveSilentTrack: AudioTrack? = null

    @Synchronized
    fun startSilentKeepAliveAudio() {
        try {
            if (keepAliveSilentTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) return
            stopSilentKeepAliveAudio()
            val rate = 8000
            val silentSamples = ShortArray(rate) // 1秒全0数字静音
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((silentSamples.size * 2).coerceAtLeast(minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(silentSamples, 0, silentSamples.size)
            track.setLoopPoints(0, silentSamples.size, -1)
            track.play()
            keepAliveSilentTrack = track
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stopSilentKeepAliveAudio() {
        try {
            keepAliveSilentTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        keepAliveSilentTrack = null
    }
}