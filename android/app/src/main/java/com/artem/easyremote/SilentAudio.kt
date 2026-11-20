package com.artem.easyremote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * An inaudible, looping audio stream that is played while the player notification is up.
 *
 * Systems (and many vendor skins) only treat a media session as a real player, with a lock-screen card and a
 * notification that cannot be swiped away, when the app is actually playing audio. The remote has no sound of
 * its own, so it plays one second of silence on repeat. No audio focus is requested, so it never interrupts or
 * ducks whatever else the phone is playing.
 */
class SilentAudio {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        try {
            val frames = RATE // one second of mono 16-bit zeros
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            t.write(ShortArray(frames), 0, frames)
            t.setLoopPoints(0, frames, -1)
            t.play()
            track = t
        } catch (e: Exception) {
            track = null
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: Exception) {
        }
        t.release()
    }

    private companion object {
        const val RATE = 8000
    }
}
