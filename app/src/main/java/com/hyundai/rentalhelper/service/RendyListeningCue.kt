package com.hyundai.rentalhelper.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short "you can speak now" tone, played only after Pleos STT capture is confirmed (audio after onReady).
 *
 * Off by default (build property rendy.voice.readyCue=after_capture enables it): the on-device speech service
 * opens its AudioRecord with ns:false, aec:false (service log), so on real hardware the tone can reach the
 * microphone. It is pure tone (no speech), 90 ms, low volume, but its effect on the endpoint detector must be
 * checked in a real vehicle before enabling it by default.
 */
class RendyListeningCue {
    private val sampleRate = 16_000
    private val pcm: ShortArray = run {
        val n = sampleRate * DURATION_MS / 1000
        ShortArray(n) { i ->
            val fade = min(1.0, min(i, n - 1 - i) / (sampleRate * 0.01)) // 10 ms fade in/out, no click
            (sin(2 * PI * FREQUENCY_HZ * i / sampleRate) * fade * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun play() {
        val started = SystemClock.elapsedRealtime()
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    Log.i(TAG, "Listening cue played: durationMs=${SystemClock.elapsedRealtime() - started}")
                    t.release()
                }
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "Listening cue failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RendyVoiceUX"
        const val DURATION_MS = 90
        private const val FREQUENCY_HZ = 880.0
        private const val AMPLITUDE = 0.25
        const val MODE_OFF = "off"
        const val MODE_AFTER_CAPTURE = "after_capture"
    }
}
