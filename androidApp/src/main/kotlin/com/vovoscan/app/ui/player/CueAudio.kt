@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vovoscan.app.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.vovoscan.feature.episodes.domain.player.AudioCue
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the audio cues of each [com.vovoscan.feature.episodes.domain.player.PlayerFrame] with media3 ExoPlayer (Ogg
 * Opus): one player per cue id, created and sought to the cue's offset when it appears, its volume and pan set every
 * frame, sought again when it drifts more than [DRIFT_MS], released when the cue goes. Pan has no ExoPlayer control:
 * each player's audio sink runs a [PanProcessor] (the Web Audio StereoPannerNode law). The next voice line is warmed
 * ahead (prepared, buffered, silent) so it starts on its beat.
 */
class CueAudio(
    private val context: Context,
    private val onFailed: (url: String) -> Unit,
) {
    private class Voice(
        val url: String,
        val player: ExoPlayer,
        val pan: PanProcessor,
    ) {
        var gain = -1f
        var checkedAt = 0L
    }

    private val active = HashMap<String, Voice>()
    private val warm = LinkedHashMap<String, Voice>()
    private val seen = HashSet<String>()

    /** Call once per display frame with the frame's cues. */
    fun sync(
        cues: List<AudioCue>,
        playing: Boolean,
        speed: Double,
        nowMs: Long,
    ) {
        seen.clear()
        for (cue in cues) {
            val url = cue.url ?: continue
            seen += cue.id
            var voice = active[cue.id]
            if (voice == null) {
                voice = warm.remove(url) ?: create(url)
                voice.player.repeatMode = if (cue.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                voice.player.seekTo(cue.offsetMs.toLong())
                voice.checkedAt = nowMs
                active[cue.id] = voice
            }
            val player = voice.player
            val gain = cue.gain.toFloat()
            if (abs(gain - voice.gain) > 0.002f) {
                player.volume = gain
                voice.gain = gain
            }
            voice.pan.pan = cue.pan.toFloat()
            if (player.playbackParameters.speed != speed.toFloat()) player.setPlaybackSpeed(speed.toFloat())
            if (player.playWhenReady != playing) player.playWhenReady = playing
            // Drift (a seek, a stall): checked a few times a second, only once the clip is playing or ready.
            if (nowMs - voice.checkedAt >= CHECK_EVERY_MS && player.playbackState == Player.STATE_READY) {
                voice.checkedAt = nowMs
                val duration = player.duration
                var drift = abs(player.currentPosition - cue.offsetMs)
                if (cue.loop && duration > 0) drift = min(drift, duration - drift)
                if (drift > DRIFT_MS) player.seekTo(cue.offsetMs.toLong())
            }
        }
        if (active.size > seen.size) {
            val gone = active.keys.filter { it !in seen }
            gone.forEach { active.remove(it)?.player?.release() }
        }
    }

    /** Prepares the clip at [url] ahead of its cue (the next beat's voice line). */
    fun warmUp(url: String) {
        if (warm.containsKey(url) || active.values.any { it.url == url }) return
        warm[url] = create(url)
        while (warm.size > WARM) warm.remove(warm.keys.first())?.player?.release()
    }

    fun pauseAll() {
        active.values.forEach { it.player.playWhenReady = false }
    }

    fun release() {
        active.values.forEach { it.player.release() }
        warm.values.forEach { it.player.release() }
        active.clear()
        warm.clear()
    }

    private fun create(url: String): Voice {
        val pan = PanProcessor()
        val renderers =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(pan)).build()
            }
        val player =
            ExoPlayer
                .Builder(context, renderers)
                // Several clips play at once: none of them takes the audio focus from the others.
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false,
                ).build()
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = onFailed(url)
            },
        )
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = false
        player.prepare()
        return Voice(url, player, pan)
    }

    private companion object {
        const val DRIFT_MS = 250
        const val CHECK_EVERY_MS = 300L
        const val WARM = 2
    }
}

/**
 * Constant-power stereo panning of 16-bit PCM, mono or stereo in, stereo out: the Web Audio StereoPannerNode law the
 * web player used (and `AVAudioPlayer.pan` approximates on iOS). [pan] −1 left … +1 right, read on the audio thread.
 */
class PanProcessor : BaseAudioProcessor() {
    @Volatile var pan: Float = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (inputAudioFormat.channelCount !in 1..2) return AudioFormat.NOT_SET
        return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frames = inputBuffer.remaining() / (2 * channels)
        val out = replaceOutputBuffer(frames * 4)
        val p = pan.coerceIn(-1f, 1f)
        if (channels == 1) {
            val x = (p + 1) / 2 * (PI / 2)
            val gl = cos(x).toFloat()
            val gr = sin(x).toFloat()
            repeat(frames) {
                val s = inputBuffer.getShort().toFloat()
                out.putShort(clip(s * gl))
                out.putShort(clip(s * gr))
            }
        } else {
            val x = (if (p <= 0) p + 1 else p) * (PI / 2)
            val gl = cos(x).toFloat()
            val gr = sin(x).toFloat()
            repeat(frames) {
                val l = inputBuffer.getShort().toFloat()
                val r = inputBuffer.getShort().toFloat()
                if (p <= 0) {
                    out.putShort(clip(l + r * gl))
                    out.putShort(clip(r * gr))
                } else {
                    out.putShort(clip(l * gl))
                    out.putShort(clip(r + l * gr))
                }
            }
        }
        out.flip()
    }

    private fun clip(v: Float): Short = v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
}
